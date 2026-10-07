// Desenha uma resposta do Euno: Markdown (marked) + fórmulas LaTeX (KaTeX), com o HTML limpo pelo DOMPurify.
// Tudo roda offline, a partir dos arquivos desta pasta; a página não acessa rede nem arquivos.
(function () {
  "use strict";
  var MATH = [
    { re: /\$\$([\s\S]+?)\$\$/g, display: true },
    { re: /\\\[([\s\S]+?)\\\]/g, display: true },
    { re: /\\\(([\s\S]+?)\\\)/g, display: false },
    // $...$ na linha; "R$ 10", "US$" e "\$" não são fórmula.
    { re: /(^|[^\\RS$])\$(?=\S)([^$\n]+?)(?<=\S)\$(?!\d)/g, display: false, keepPrefix: true },
  ];

  function protect(text) {
    var codes = [];
    var maths = [];
    var t = text.replace(/```[\s\S]*?(```|$)|`[^`\n]+`/g, function (m) {
      codes.push(m);
      return "EUNOCODE" + (codes.length - 1) + "X";
    });
    MATH.forEach(function (rule) {
      t = t.replace(rule.re, function (m, a, b) {
        var prefix = rule.keepPrefix ? a : "";
        var tex = rule.keepPrefix ? b : a;
        maths.push({ tex: tex, display: rule.display });
        return prefix + "EUNOMATH" + (maths.length - 1) + "X";
      });
    });
    t = t.replace(/EUNOCODE(\d+)X/g, function (m, i) { return codes[+i]; });
    return { text: t, maths: maths };
  }

  function renderMath(m) {
    try {
      return katex.renderToString(m.tex, { displayMode: m.display, throwOnError: false, output: "html", trust: false, strict: "ignore" });
    } catch (e) {
      var span = document.createElement("span");
      span.className = "math-error";
      span.textContent = m.tex;
      return span.outerHTML;
    }
  }

  function setTheme(theme) {
    if (!theme) return;
    var root = document.documentElement.style;
    if (theme.fg) root.setProperty("--fg", theme.fg);
    if (theme.muted) root.setProperty("--muted", theme.muted);
    if (theme.accent) root.setProperty("--accent", theme.accent);
    if (theme.code) root.setProperty("--code", theme.code);
  }

  function reportHeight() {
    var h = Math.ceil(document.getElementById("c").getBoundingClientRect().height);
    if (window.EunoBridge) window.EunoBridge.onHeight(h);
  }

  window.eunoRender = function (text, theme) {
    setTheme(theme);
    var p = protect(text || "");
    var html = marked.parse(p.text, { gfm: true, breaks: true });
    html = DOMPurify.sanitize(html, { USE_PROFILES: { html: true }, FORBID_TAGS: ["img", "picture", "video", "audio", "iframe", "form", "input", "style", "svg"], FORBID_ATTR: ["style"] });
    // As fórmulas entram depois da limpeza: o KaTeX gera o HTML a partir do LaTeX (sem \href nem HTML cru, trust: false).
    html = html.replace(/EUNOMATH(\d+)X/g, function (m, i) { return renderMath(p.maths[+i]); });
    document.getElementById("c").innerHTML = html;
    reportHeight();
  };

  if (window.ResizeObserver) {
    window.addEventListener("load", function () {
      new ResizeObserver(reportHeight).observe(document.getElementById("c"));
    });
  }
})();
