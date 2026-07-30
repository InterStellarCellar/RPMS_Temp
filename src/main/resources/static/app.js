(function () {
  const DEBOUNCE_MS = 400;
  const MAX_BYTES = 2 * 1024 * 1024;
  const MONACO_BASE = "https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.45.0/min/vs";
  const NON_PATH_LOCATIONS = {
    JSON: true,
    "FHIR parse": true,
    resourceType: true,
    "JSON.parse": true,
  };

  let editor;
  let debounceTimer;
  let abortCtrl;
  let skipBlurValidate = false;
  let lastIssuePositions = [];
  let activeIssueIndex = -1;

  const defaultSample = `{
  "resourceType": "Questionnaire",
  "text": {
    "status": "generated",
    "div": "<div xmlns=\\"http://www.w3.org/1999/xhtml\\">Example Questionnaire</div>"
  },
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

  function initIssuesPanel() {
    const box = el("issues");
    const bottomPanel = el("bottom-panel");
    if (!box) return;

    let issuePointerDown = null;

    if (bottomPanel) {
      bottomPanel.addEventListener("mousedown", function (e) {
        if (e.target.closest("#issues .issue")) {
          skipBlurValidate = true;
        }
      });
    }

    box.addEventListener("mousedown", function (e) {
      const row = e.target.closest(".issue");
      if (!row) {
        issuePointerDown = null;
        return;
      }
      issuePointerDown = { x: e.clientX, y: e.clientY, row: row };
    });

    box.addEventListener("click", function (e) {
      const row = e.target.closest(".issue");
      if (!row) return;

      // Allow highlight + copy: skip navigation when the user selected text or dragged.
      const selection = window.getSelection();
      if (selection && selection.toString().length > 0) {
        issuePointerDown = null;
        return;
      }
      if (issuePointerDown && issuePointerDown.row === row) {
        const dx = Math.abs(e.clientX - issuePointerDown.x);
        const dy = Math.abs(e.clientY - issuePointerDown.y);
        if (dx > 4 || dy > 4) {
          issuePointerDown = null;
          return;
        }
      }
      issuePointerDown = null;

      const index = Number(row.dataset.issueIndex);
      if (Number.isNaN(index) || !lastIssuePositions[index]) return;
      activeIssueIndex = index;
      focusIssueInEditor(lastIssuePositions[index], row);
    });

    box.addEventListener("keydown", function (e) {
      const row = e.target.closest(".issue");
      if (!row) return;
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        const index = Number(row.dataset.issueIndex);
        if (Number.isNaN(index) || !lastIssuePositions[index]) return;
        activeIssueIndex = index;
        focusIssueInEditor(lastIssuePositions[index], row);
      }
    });
  }

  function initPanelResize() {
    const panel = el("bottom-panel");
    const handle = el("panel-resize-handle");
    if (!panel || !handle) return;

    let dragging = false;
    let startY = 0;
    let startHeight = 0;

    function onPointerMove(e) {
      if (!dragging) return;
      const delta = startY - e.clientY;
      const min = parseFloat(getComputedStyle(panel).minHeight) || 72;
      const max = Math.min(window.innerHeight * 0.7, window.innerHeight - 120);
      const next = Math.max(min, Math.min(max, startHeight + delta));
      panel.style.height = next + "px";
      if (editor) editor.layout();
    }

    function stopDrag() {
      if (!dragging) return;
      dragging = false;
      handle.classList.remove("active");
      document.body.style.cursor = "";
      document.body.style.userSelect = "";
      window.removeEventListener("pointermove", onPointerMove);
      window.removeEventListener("pointerup", stopDrag);
    }

    handle.addEventListener("pointerdown", function (e) {
      dragging = true;
      startY = e.clientY;
      startHeight = panel.getBoundingClientRect().height;
      handle.classList.add("active");
      document.body.style.cursor = "row-resize";
      document.body.style.userSelect = "none";
      window.addEventListener("pointermove", onPointerMove);
      window.addEventListener("pointerup", stopDrag);
      e.preventDefault();
    });
  }

  function setStatus(text, ok) {
    const s = el("status");
    s.textContent = text;
    s.className = ok === true ? "ok" : ok === false ? "bad" : "";
  }

  // HAPI ResultSeverityEnum: information, warning, error, fatal (no others)
  function normalizeSeverity(sev) {
    const l = String(sev || "unknown").toLowerCase();
    if (l === "information" || l === "info") return "information";
    if (l === "warning") return "warning";
    if (l === "error") return "error";
    if (l === "fatal") return "fatal";
    return "unknown";
  }

  function severityCssClass(sev) {
    return "sev-" + normalizeSeverity(sev);
  }

  function formatSeverityLabel(sev) {
    return normalizeSeverity(sev).toUpperCase();
  }

  function mapSeverity(sev) {
    const level = normalizeSeverity(sev);
    if (level === "error" || level === "fatal") return monaco.MarkerSeverity.Error;
    if (level === "warning") return monaco.MarkerSeverity.Warning;
    return monaco.MarkerSeverity.Info;
  }

  function offsetToLineCol(text, offset) {
    let line = 1;
    let col = 1;
    for (let i = 0; i < offset && i < text.length; i++) {
      if (text.charCodeAt(i) === 10) {
        line++;
        col = 1;
      } else {
        col++;
      }
    }
    return { line: line, column: col };
  }

  function parseFhirLocation(location) {
    if (!location || typeof location !== "string") return null;
    if (NON_PATH_LOCATIONS[location]) return null;
    const segments = [];
    const re = /([A-Za-z_][A-Za-z0-9_]*)(?:\[(\d+)\])?/g;
    let m;
    while ((m = re.exec(location)) !== null) {
      segments.push({
        name: m[1],
        index: m[2] != null ? parseInt(m[2], 10) : null,
      });
    }
    return segments.length ? segments : null;
  }

  function skipWs(text, i) {
    while (i < text.length) {
      const c = text.charCodeAt(i);
      if (c === 32 || c === 9 || c === 10 || c === 13) i++;
      else break;
    }
    return i;
  }

  function readString(text, i) {
    if (text[i] !== '"') return null;
    const start = i;
    i++;
    let out = "";
    while (i < text.length) {
      const c = text[i];
      if (c === "\\") {
        out += text[i + 1] || "";
        i += 2;
        continue;
      }
      if (c === '"') {
        return { value: out, end: i + 1, start: start };
      }
      out += c;
      i++;
    }
    return null;
  }

  function skipValue(text, i) {
    i = skipWs(text, i);
    const c = text[i];
    if (c === '"') {
      const s = readString(text, i);
      return s ? s.end : -1;
    }
    if (c === "{") return skipObject(text, i).end;
    if (c === "[") return skipArray(text, i).end;
    if (c === "t" || c === "f" || c === "n" || (c >= "0" && c <= "9") || c === "-") {
      while (i < text.length && /[^\s,}\]]/.test(text[i])) i++;
      return i;
    }
    return -1;
  }

  function skipObject(text, i) {
    i = skipWs(text, i);
    if (text[i] !== "{") return { end: -1 };
    const start = i;
    i++;
    i = skipWs(text, i);
    if (text[i] === "}") return { start: start, end: i + 1, props: [] };
    const props = [];
    while (i < text.length) {
      i = skipWs(text, i);
      const keyTok = readString(text, i);
      if (!keyTok) return { end: -1 };
      const keyStart = i;
      const keyEnd = keyTok.end;
      i = skipWs(text, keyTok.end);
      if (text[i] !== ":") return { end: -1 };
      i++;
      i = skipWs(text, i);
      const valueStart = i;
      const valueEnd = skipValue(text, i);
      if (valueEnd < 0) return { end: -1 };
      props.push({
        key: keyTok.value,
        keyStart: keyStart,
        keyEnd: keyEnd,
        valueStart: valueStart,
        valueEnd: valueEnd,
      });
      i = skipWs(text, valueEnd);
      if (text[i] === ",") {
        i++;
        continue;
      }
      if (text[i] === "}") return { start: start, end: i + 1, props: props };
      return { end: -1 };
    }
    return { end: -1 };
  }

  function skipArray(text, i) {
    i = skipWs(text, i);
    if (text[i] !== "[") return { end: -1 };
    const start = i;
    i++;
    i = skipWs(text, i);
    if (text[i] === "]") return { start: start, end: i + 1, elements: [] };
    const elements = [];
    while (i < text.length) {
      i = skipWs(text, i);
      const valueStart = i;
      const valueEnd = skipValue(text, i);
      if (valueEnd < 0) return { end: -1 };
      elements.push({ start: valueStart, end: valueEnd });
      i = skipWs(text, valueEnd);
      if (text[i] === ",") {
        i++;
        continue;
      }
      if (text[i] === "]") return { start: start, end: i + 1, elements: elements };
      return { end: -1 };
    }
    return { end: -1 };
  }

  function editDistance(a, b) {
    if (a === b) return 0;
    const m = a.length;
    const n = b.length;
    if (Math.abs(m - n) > 2) return 99;
    const dp = new Array(n + 1);
    for (let j = 0; j <= n; j++) dp[j] = j;
    for (let i = 1; i <= m; i++) {
      let prev = dp[0];
      dp[0] = i;
      for (let j = 1; j <= n; j++) {
        const tmp = dp[j];
        const cost = a[i - 1] === b[j - 1] ? 0 : 1;
        dp[j] = Math.min(dp[j] + 1, dp[j - 1] + 1, prev + cost);
        prev = tmp;
      }
    }
    return dp[n];
  }

  function findClosestKey(props, wanted) {
    let best = null;
    let bestDist = 3;
    for (let i = 0; i < props.length; i++) {
      const d = editDistance(props[i].key.toLowerCase(), wanted.toLowerCase());
      if (d > 0 && d < bestDist) {
        bestDist = d;
        best = props[i];
      }
    }
    return best;
  }

  /**
   * Resolve a FHIRPath-like location against pretty-printed JSON.
   * For missing leaf properties, highlights a near-miss key (e.g. linkI) or the parent object.
   */
  function resolveLocationInJson(text, location) {
    const segments = parseFhirLocation(location);
    if (!segments) return null;

    let path = segments.slice();
    // FHIR paths are rooted at the resource type; JSON root is that resource.
    if (path.length && path[0].name === "Questionnaire") {
      path = path.slice(1);
    }

    let nodeStart = skipWs(text, 0);
    if (text[nodeStart] !== "{") return null;

    let current = skipObject(text, nodeStart);
    if (current.end < 0) return null;

    if (!path.length) {
      const start = offsetToLineCol(text, current.start);
      return {
        line: start.line,
        column: start.column,
        endLine: start.line,
        endColumn: start.column + 1,
      };
    }

    for (let s = 0; s < path.length; s++) {
      const seg = path[s];
      const isLast = s === path.length - 1;
      const prop = current.props.find(function (p) {
        return p.key === seg.name;
      });

      if (!prop) {
        const near = findClosestKey(current.props, seg.name);
        if (near) {
          const start = offsetToLineCol(text, near.keyStart);
          const end = offsetToLineCol(text, near.keyEnd);
          return {
            line: start.line,
            column: start.column,
            endLine: end.line,
            endColumn: end.column,
          };
        }
        const start = offsetToLineCol(text, current.start);
        return {
          line: start.line,
          column: start.column,
          endLine: start.line,
          endColumn: start.column + 1,
        };
      }

      if (seg.index != null) {
        const arr = skipArray(text, prop.valueStart);
        if (arr.end < 0 || !arr.elements[seg.index]) {
          const start = offsetToLineCol(text, prop.keyStart);
          const end = offsetToLineCol(text, prop.keyEnd);
          return {
            line: start.line,
            column: start.column,
            endLine: end.line,
            endColumn: end.column,
          };
        }
        const el = arr.elements[seg.index];
        if (isLast) {
          const start = offsetToLineCol(text, el.start);
          return {
            line: start.line,
            column: start.column,
            endLine: start.line,
            endColumn: start.column + 1,
          };
        }
        const next = skipWs(text, el.start);
        if (text[next] === "{") {
          current = skipObject(text, next);
          if (current.end < 0) return null;
          continue;
        }
        return null;
      }

      if (isLast) {
        const start = offsetToLineCol(text, prop.keyStart);
        const end = offsetToLineCol(text, prop.keyEnd);
        return {
          line: start.line,
          column: start.column,
          endLine: end.line,
          endColumn: end.column,
        };
      }

      const next = skipWs(text, prop.valueStart);
      if (text[next] === "{") {
        current = skipObject(text, next);
        if (current.end < 0) return null;
        continue;
      }
      if (text[next] === "[") {
        // Path like Questionnaire.item.linkId without [0] — use first element
        const arr = skipArray(text, next);
        if (arr.end < 0 || !arr.elements.length) {
          const start = offsetToLineCol(text, prop.keyStart);
          const end = offsetToLineCol(text, prop.keyEnd);
          return {
            line: start.line,
            column: start.column,
            endLine: end.line,
            endColumn: end.column,
          };
        }
        const el0 = arr.elements[0];
        const elStart = skipWs(text, el0.start);
        if (text[elStart] !== "{") return null;
        current = skipObject(text, elStart);
        if (current.end < 0) return null;
        continue;
      }
      return null;
    }
    return null;
  }

  function hapiPositionLooksPlausible(issue, model) {
    if (issue.line == null || issue.column == null) return false;
    if (issue.line < 1 || issue.line > model.getLineCount()) return false;
    const lineLen = model.getLineLength(issue.line);
    if (issue.column < 1 || issue.column > lineLen + 1) return false;
    // HAPI often reports 1:<large-col> for missing-element errors on pretty JSON
    if (issue.line === 1 && model.getLineCount() > 1 && issue.column > 40) return false;
    return true;
  }

  function resolveIssuePosition(issue, text, model) {
    const fromPath = resolveLocationInJson(text, issue.location);
    if (fromPath) return fromPath;

    if (hapiPositionLooksPlausible(issue, model)) {
      return {
        line: issue.line,
        column: issue.column,
        endLine: issue.line,
        endColumn: Math.min(issue.column + 1, model.getLineLength(issue.line) + 1),
      };
    }

    return { line: 1, column: 1, endLine: 1, endColumn: 2 };
  }

  function issuesToMarkers(issues, model, text) {
    const source = text != null ? text : model.getValue();
    return issues.map(function (issue) {
      const pos = resolveIssuePosition(issue, source, model);
      const msg = issue.message + (issue.location ? " — " + issue.location : "");
      return {
        severity: mapSeverity(issue.severity),
        message: msg,
        startLineNumber: pos.line,
        startColumn: pos.column,
        endLineNumber: pos.endLine,
        endColumn: pos.endColumn,
      };
    });
  }

  function focusIssueInEditor(pos, activeRow) {
    if (!editor || !pos || pos.line == null) return;

    const model = editor.getModel();
    if (!model) return;

    const line = Math.max(1, Math.min(pos.line, model.getLineCount()));
    const lineLen = model.getLineLength(line);
    const column = Math.max(1, Math.min(pos.column != null ? pos.column : 1, lineLen + 1));
    const endLine = Math.max(
      line,
      Math.min(pos.endLine != null ? pos.endLine : line, model.getLineCount()),
    );
    const endLineLen = model.getLineLength(endLine);
    let endColumn = Math.max(
      column + 1,
      Math.min(pos.endColumn != null ? pos.endColumn : column + 1, endLineLen + 1),
    );
    if (endLine === line && endColumn <= column) {
      endColumn = Math.min(column + 1, lineLen + 1);
    }

    const range = {
      startLineNumber: line,
      startColumn: column,
      endLineNumber: endLine,
      endColumn: endColumn,
    };

    editor.setSelection(range);
    editor.revealRangeInCenter(range);
    editor.focus();

    const box = el("issues");
    if (box) {
      box.querySelectorAll(".issue").forEach(function (row) {
        row.classList.toggle("issue-active", row === activeRow);
      });
    }
  }

  function renderIssueList(issues, model, text) {
    const box = el("issues");
    box.replaceChildren();
    if (!issues || !issues.length) {
      lastIssuePositions = [];
      activeIssueIndex = -1;
      return;
    }
    const source = text != null ? text : model ? model.getValue() : "";
    lastIssuePositions = issues.map(function (issue) {
      return model && source
        ? resolveIssuePosition(issue, source, model)
        : { line: issue.line, column: issue.column };
    });
    if (activeIssueIndex >= issues.length) {
      activeIssueIndex = -1;
    }

    issues.forEach(function (issue, index) {
      const pos = lastIssuePositions[index];
      const row = document.createElement("div");
      row.className = "issue " + severityCssClass(issue.severity);
      row.dataset.issueIndex = String(index);
      row.setAttribute("role", "button");
      row.setAttribute("tabindex", "0");
      row.title = "Go to line " + pos.line;
      row.textContent =
        formatSeverityLabel(issue.severity) +
        " @ " +
        (pos.line != null ? pos.line : "?") +
        ":" +
        (pos.column != null ? pos.column : "?") +
        " — " +
        issue.message;
      if (index === activeIssueIndex) {
        row.classList.add("issue-active");
      }
      box.appendChild(row);
    });
  }

  function localJsonCheck(text) {
    try {
      JSON.parse(text);
      return null;
    } catch (e) {
      const m = e.message || String(e);
      return [
        {
          severity: "fatal",
          message: "JSON syntax: " + m,
          line: 1,
          column: 1,
          location: "JSON.parse",
        },
      ];
    }
  }

  function scheduleValidate() {
    if (el("optBlur").checked) return;
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(runValidate, DEBOUNCE_MS);
  }

  function runValidate() {
    const text = editor.getValue();
    const enc = new TextEncoder();
    if (enc.encode(text).length > MAX_BYTES) {
      setStatus("Payload exceeds 2 MiB limit.", false);
      monaco.editor.setModelMarkers(editor.getModel(), "fhir", [
        {
          severity: monaco.MarkerSeverity.Error,
          message: "Document exceeds maximum size for this demo.",
          startLineNumber: 1,
          startColumn: 1,
          endLineNumber: 1,
          endColumn: 2,
        },
      ]);
      renderIssueList([]);
      return;
    }

    const localIssues = localJsonCheck(text);
    if (localIssues) {
      const model = editor.getModel();
      monaco.editor.setModelMarkers(model, "fhir", issuesToMarkers(localIssues, model, text));
      setStatus(localIssues.length + " issue(s) (JSON syntax).", false);
      renderIssueList(localIssues, model, text);
      return;
    }

    if (abortCtrl) abortCtrl.abort();
    abortCtrl = new AbortController();

    setStatus("Validating…", undefined);

    const params = buildTerminologyParams();
    const url =
      "/api/validate-questionnaire" + (params.toString() ? "?" + params.toString() : "");

    fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: text,
      signal: abortCtrl.signal,
    })
      .then(function (r) {
        if (!r.ok) {
          return r.text().then(function (body) {
            throw new Error("HTTP " + r.status + (body ? ": " + body : ""));
          });
        }
        return r.json();
      })
      .then(function (data) {
        const model = editor.getModel();
        const issues = data.issues || [];
        monaco.editor.setModelMarkers(model, "fhir", issuesToMarkers(issues, model, text));
        const n = issues.length;
        setStatus(data.ok ? "Valid (" + n + " message(s))." : n + " issue(s).", data.ok);
        renderIssueList(issues, model, text);
      })
      .catch(function (err) {
        if (err.name === "AbortError") return;
        setStatus("Request failed: " + err.message, false);
      });
  }

  function buildTerminologyParams() {
    const params = new URLSearchParams();
    const select = el("terminologyServer");
    const custom = el("terminologyUrl");
    if (!select) return params;
    if (select.value === "custom") {
      const url = custom && custom.value ? custom.value.trim() : "";
      if (url) params.set("terminologyUrl", url);
      else params.set("terminologyServer", "none");
    } else if (select.value) {
      params.set("terminologyServer", select.value);
    }
    return params;
  }

  function syncCustomUrlVisibility() {
    const select = el("terminologyServer");
    const custom = el("terminologyUrl");
    if (!select || !custom) return;
    if (select.value === "custom") {
      custom.classList.remove("hidden");
    } else {
      custom.classList.add("hidden");
    }
  }

  function loadTerminologyServers() {
    const select = el("terminologyServer");
    if (!select) return Promise.resolve();
    return fetch("/api/terminology-servers")
      .then(function (r) {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.json();
      })
      .then(function (servers) {
        select.replaceChildren();
        (servers || []).forEach(function (s) {
          const opt = document.createElement("option");
          opt.value = s.id;
          opt.textContent = s.label;
          if (s.url) opt.title = s.url;
          select.appendChild(opt);
        });
        const customOpt = document.createElement("option");
        customOpt.value = "custom";
        customOpt.textContent = "Custom URL…";
        select.appendChild(customOpt);
        if (!select.value) select.value = "none";
        syncCustomUrlVisibility();
      })
      .catch(function (err) {
        select.replaceChildren();
        const fallback = document.createElement("option");
        fallback.value = "none";
        fallback.textContent = "Local only (failed to load presets)";
        select.appendChild(fallback);
        setStatus("Could not load terminology servers: " + err.message, false);
      });
  }

  initPanelResize();
  initIssuesPanel();

  require.config({ paths: { vs: MONACO_BASE } });
  require(["vs/editor/editor.main"], function () {
    editor = monaco.editor.create(el("editor"), {
      value: defaultSample,
      language: "json",
      theme: "vs-dark",
      automaticLayout: true,
      minimap: { enabled: true },
      tabSize: 2,
    });

    editor.onDidChangeModelContent(scheduleValidate);

    el("optBlur").addEventListener("change", function () {
      if (!el("optBlur").checked) scheduleValidate();
    });

    el("btnValidate").addEventListener("click", runValidate);

    el("terminologyServer").addEventListener("change", function () {
      syncCustomUrlVisibility();
      scheduleValidate();
    });
    el("terminologyUrl").addEventListener("change", scheduleValidate);
    el("terminologyUrl").addEventListener("keydown", function (e) {
      if (e.key === "Enter") {
        e.preventDefault();
        runValidate();
      }
    });

    editor.onDidBlurEditorWidget(function () {
      if (skipBlurValidate) {
        skipBlurValidate = false;
        return;
      }
      if (el("optBlur").checked) runValidate();
    });

    loadTerminologyServers().then(function () {
      runValidate();
    });
  });
})();
