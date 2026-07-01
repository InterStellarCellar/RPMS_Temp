(function () {
	const DEBOUNCE_MS = 400;
	const MAX_BYTES = 2 * 1024 * 1024;
	const MONACO_BASE = 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.45.0/min/vs';

	let editor;
	let debounceTimer;
	let abortCtrl;

	const defaultSample = `{
  "resourceType": "Questionnaire",
  "status": "draft",
  "item": [
    {
      "linkId": "1",
      "type": "string",
      "text": "Example"
    }
  ]
}`;

	function el(id) {
		return document.getElementById(id);
	}

	function setStatus(text, ok) {
		const s = el('status');
		s.textContent = text;
		s.className = ok === true ? 'ok' : ok === false ? 'bad' : '';
	}

	function mapSeverity(sev) {
		if (!sev) return monaco.MarkerSeverity.Info;
		const l = String(sev).toLowerCase();
		if (l === 'error' || l === 'fatal') return monaco.MarkerSeverity.Error;
		if (l === 'warning') return monaco.MarkerSeverity.Warning;
		return monaco.MarkerSeverity.Info;
	}

	function issuesToMarkers(issues, model) {
		return issues.map(function (issue) {
			const line = issue.line != null ? issue.line : 1;
			const col = issue.column != null ? issue.column : 1;
			const lineLen = model.getLineLength(line);
			const msg =
				issue.message +
				(issue.location ? ' — ' + issue.location : '');
			return {
				severity: mapSeverity(issue.severity),
				message: msg,
				startLineNumber: line,
				startColumn: col,
				endLineNumber: line,
				endColumn: Math.min(col + 1, Math.max(col, lineLen + 1))
			};
		});
	}

	function renderIssueList(issues) {
		const box = el('issues');
		if (!issues || !issues.length) {
			box.textContent = '';
			return;
		}
		box.textContent = issues
			.map(function (i) {
				return (
					(i.severity || '?') +
					' @ ' +
					(i.line != null ? i.line : '?') +
					':' +
					(i.column != null ? i.column : '?') +
					' — ' +
					i.message
				);
			})
			.join('\n');
	}

	function localJsonCheck(text) {
		try {
			JSON.parse(text);
			return null;
		} catch (e) {
			const m = e.message || String(e);
			const line = 1;
			const col = 1;
			return [
				{
					severity: 'fatal',
					message: 'JSON syntax: ' + m,
					line: line,
					column: col,
					location: 'JSON.parse'
				}
			];
		}
	}

	function scheduleValidate() {
		if (el('optBlur').checked) return;
		clearTimeout(debounceTimer);
		debounceTimer = setTimeout(runValidate, DEBOUNCE_MS);
	}

	function runValidate() {
		const text = editor.getValue();
		const enc = new TextEncoder();
		if (enc.encode(text).length > MAX_BYTES) {
			setStatus('Payload exceeds 2 MiB limit.', false);
			monaco.editor.setModelMarkers(editor.getModel(), 'fhir', [
				{
					severity: monaco.MarkerSeverity.Error,
					message: 'Document exceeds maximum size for this demo.',
					startLineNumber: 1,
					startColumn: 1,
					endLineNumber: 1,
					endColumn: 2
				}
			]);
			renderIssueList([]);
			return;
		}

		const localIssues = localJsonCheck(text);
		if (localIssues) {
			const model = editor.getModel();
			monaco.editor.setModelMarkers(model, 'fhir', issuesToMarkers(localIssues, model));
			setStatus(localIssues.length + ' issue(s) (JSON syntax).', false);
			renderIssueList(localIssues);
			return;
		}

		if (abortCtrl) abortCtrl.abort();
		abortCtrl = new AbortController();

		const params = new URLSearchParams();
		if (el('optXsd').checked) params.set('xsd', 'true');
		if (el('optSch').checked) params.set('schematron', 'true');

		setStatus('Validating…', undefined);

		fetch('/api/validate-questionnaire?' + params.toString(), {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: text,
			signal: abortCtrl.signal
		})
			.then(function (r) {
				if (!r.ok) throw new Error('HTTP ' + r.status);
				return r.json();
			})
			.then(function (data) {
				const model = editor.getModel();
				const markers = issuesToMarkers(data.issues || [], model);
				monaco.editor.setModelMarkers(model, 'fhir', markers);
				const n = (data.issues && data.issues.length) || 0;
				setStatus(
					data.ok ? 'Valid (' + n + ' message(s)).' : n + ' issue(s).',
					data.ok
				);
				renderIssueList(data.issues || []);
			})
			.catch(function (err) {
				if (err.name === 'AbortError') return;
				setStatus('Request failed: ' + err.message, false);
			});
	}

	require.config({ paths: { vs: MONACO_BASE } });
	require(['vs/editor/editor.main'], function () {
		editor = monaco.editor.create(el('editor'), {
			value: defaultSample,
			language: 'json',
			theme: 'vs-dark',
			automaticLayout: true,
			minimap: { enabled: true },
			tabSize: 2
		});

		editor.onDidChangeModelContent(scheduleValidate);

		el('optBlur').addEventListener('change', function () {
			if (!el('optBlur').checked) scheduleValidate();
		});

		el('btnValidate').addEventListener('click', runValidate);

		editor.onDidBlurEditorWidget(function () {
			if (el('optBlur').checked) runValidate();
		});

		runValidate();
	});
})();
