(function () {
  if (window.__haPanel && window.__haPanel.reapply) {
    window.__haPanel.reapply();
    return;
  }

  var STYLE_ID = "hap-kiosk-style";
  var INTERVAL_MS = 800;
  var lastReport = "";
  var applying = false;

  function isAuthPage() {
    var p = location.pathname || "";
    return p.indexOf("/auth") === 0 || p.indexOf("/api/") === 0;
  }

  function findDeep(root, selector, depth) {
    if (!root || depth > 14) return null;
    try {
      var el = root.querySelector ? root.querySelector(selector) : null;
      if (el) return el;
    } catch (e) {}
    var nodes = [];
    if (root.shadowRoot) nodes.push(root.shadowRoot);
    var ch = root.children;
    if (ch) {
      for (var i = 0; i < ch.length; i++) nodes.push(ch[i]);
    }
    for (var j = 0; j < nodes.length; j++) {
      var f = findDeep(nodes[j], selector, depth + 1);
      if (f) return f;
    }
    return null;
  }

  function walkShadows(root, fn, depth) {
    if (!root || depth > 16) return;
    fn(root, depth);
    if (root.shadowRoot) walkShadows(root.shadowRoot, fn, depth + 1);
    var ch = root.children;
    if (!ch) return;
    for (var i = 0; i < ch.length; i++) walkShadows(ch[i], fn, depth + 1);
  }

  function ensureStyle(root, css) {
    if (!root) return;
    var host = root;
    // For shadow roots and document
    var target = root.head || root;
    var el = null;
    try {
      el = (root.getElementById && root.getElementById(STYLE_ID)) ||
           (root.querySelector && root.querySelector("#" + STYLE_ID));
    } catch (e) {}
    if (!el) {
      el = document.createElement("style");
      el.id = STYLE_ID;
      try {
        target.appendChild(el);
      } catch (e2) {
        try { root.appendChild(el); } catch (e3) { return; }
      }
    }
    if (el.textContent !== css) el.textContent = css;
  }

  var DRAWER_CSS =
    ".sidebar-shell{display:none!important;width:0!important;min-width:0!important;max-width:0!important;overflow:hidden!important;visibility:hidden!important;}" +
    ".app-content{padding-left:0!important;padding-inline-start:0!important;padding-right:0!important;padding-inline-end:0!important;width:100%!important;margin-left:0!important;}" +
    ".layout{padding-left:0!important;padding-inline-start:0!important;}";

  var SIDEBAR_CSS =
    ":host{display:none!important;width:0!important;min-width:0!important;height:0!important;overflow:hidden!important;visibility:hidden!important;}";

  var HUI_CSS =
    ".header,app-header,ha-top-app-bar-fixed,app-toolbar,.toolbar{display:none!important;height:0!important;min-height:0!important;overflow:hidden!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important;}" +
    "#view,hui-view-container{padding-top:0!important;margin-top:0!important;min-height:100vh!important;}";

  function hideEl(el) {
    if (!el) return;
    try {
      el.style.setProperty("display", "none", "important");
      el.style.setProperty("width", "0", "important");
      el.style.setProperty("min-width", "0", "important");
      el.style.setProperty("visibility", "hidden", "important");
    } catch (e) {}
  }

  function applyChrome() {
    if (isAuthPage()) return false;
    var ha = document.querySelector("home-assistant");
    if (!ha || !ha.shadowRoot) return false;
    var main = ha.shadowRoot.querySelector("home-assistant-main");
    if (!main) return false;

    var drawer = findDeep(ha, "ha-drawer", 0);
    if (drawer) {
      if (drawer.shadowRoot) ensureStyle(drawer.shadowRoot, DRAWER_CSS);
      try {
        drawer.style.setProperty("--mdc-drawer-width", "0px");
        drawer.style.setProperty("--ha-drawer-width", "0px");
        drawer.style.setProperty("--app-drawer-width", "0px");
        drawer.style.setProperty("--sidebar-width", "0px");
      } catch (e) {}
    }

    var sidebar = findDeep(ha, "ha-sidebar", 0);
    if (sidebar) {
      hideEl(sidebar);
      if (sidebar.shadowRoot) ensureStyle(sidebar.shadowRoot, SIDEBAR_CSS);
    }

    var hui = findDeep(ha, "hui-root", 0);
    if (hui && hui.shadowRoot) {
      ensureStyle(hui.shadowRoot, HUI_CSS);
      var header = hui.shadowRoot.querySelector(".header") ||
                   hui.shadowRoot.querySelector("app-header") ||
                   hui.shadowRoot.querySelector("ha-top-app-bar-fixed");
      hideEl(header);
      var view = hui.shadowRoot.querySelector("#view") ||
                 hui.shadowRoot.querySelector("hui-view-container");
      if (view) {
        try {
          view.style.setProperty("padding-top", "0", "important");
          view.style.setProperty("margin-top", "0", "important");
        } catch (e2) {}
      }
    }
    return !!(drawer || hui);
  }

  function visibleViews(cfgViews) {
    var out = [];
    if (!cfgViews) return out;
    for (var i = 0; i < cfgViews.length; i++) {
      var v = cfgViews[i];
      if (!v) continue;
      if (v.subview) continue;
      if (v.visible === false) continue;
      out.push({
        title: v.title || "",
        path: (v.path != null && String(v.path).length) ? String(v.path) : String(i),
        index: i
      });
    }
    return out;
  }

  function dashboardPath(hui) {
    var urlPath = null;
    try {
      if (hui && hui.lovelace && hui.lovelace.urlPath != null && hui.lovelace.urlPath !== "") {
        urlPath = String(hui.lovelace.urlPath);
      }
    } catch (e) {}
    if (urlPath) return urlPath;
    var parts = (location.pathname || "").split("/").filter(Boolean);
    return parts.length ? parts[0] : "lovelace";
  }

  function getState() {
    var ha = document.querySelector("home-assistant");
    var hui = findDeep(ha, "hui-root", 0);
    var views = [];
    try {
      if (hui && hui.lovelace && hui.lovelace.config) {
        views = visibleViews(hui.lovelace.config.views);
      }
    } catch (e) {}
    var dash = dashboardPath(hui);
    var parts = (location.pathname || "").split("/").filter(Boolean);
    var currentPath = parts.slice(1).join("/");
    var index = 0;
    var found = false;
    for (var i = 0; i < views.length; i++) {
      if (views[i].path === currentPath || String(views[i].index) === currentPath) {
        index = i;
        found = true;
        break;
      }
    }
    if (!found && !currentPath && views.length) index = 0;
    return { views: views, index: index, dashboard: dash, path: currentPath };
  }

  function report() {
    var st = getState();
    var payload = JSON.stringify(st);
    if (payload === lastReport) return st;
    lastReport = payload;
    try {
      if (window.HAPanelBridge && window.HAPanelBridge.onViews) {
        window.HAPanelBridge.onViews(payload);
      }
    } catch (e) {}
    return st;
  }

  function haNavigate(path) {
    try {
      history.pushState(null, "", path);
    } catch (e) {
      try { location.assign(path); return; } catch (e2) { return; }
    }
    var ev;
    try {
      ev = new CustomEvent("location-changed", {
        bubbles: true,
        composed: true,
        detail: { replace: false }
      });
    } catch (e3) {
      ev = document.createEvent("Event");
      ev.initEvent("location-changed", true, true);
      ev.detail = { replace: false };
    }
    window.dispatchEvent(ev);
  }

  function viewUrl(st, view) {
    var dash = st.dashboard || "lovelace";
    var path = view.path != null ? view.path : String(view.index);
    return "/" + dash + "/" + path;
  }

  function swipe(dir) {
    applyChrome();
    var st = getState();
    if (!st.views || st.views.length < 2) {
      report();
      return "single:" + (st.dashboard || "") + "/" + (st.path || "");
    }
    var n = st.views.length;
    var idx = ((st.index + dir) % n + n) % n;
    var url = viewUrl(st, st.views[idx]);
    haNavigate(url);
    setTimeout(function () {
      applyChrome();
      report();
    }, 50);
    setTimeout(function () {
      applyChrome();
      report();
    }, 400);
    return url;
  }

  function setupObservers() {
    try {
      var mo = new MutationObserver(function () {
        if (applying) return;
        applying = true;
        applyChrome();
        report();
        applying = false;
      });
      mo.observe(document.documentElement, { childList: true, subtree: true });
      var ha = document.querySelector("home-assistant");
      if (ha && ha.shadowRoot) mo.observe(ha.shadowRoot, { childList: true, subtree: true });
    } catch (e) {}
    window.addEventListener("location-changed", function () {
      setTimeout(function () { applyChrome(); report(); }, 0);
      setTimeout(function () { applyChrome(); report(); }, 250);
    });
    window.addEventListener("popstate", function () {
      setTimeout(function () { applyChrome(); report(); }, 0);
    });
    setInterval(function () {
      applyChrome();
      report();
    }, INTERVAL_MS);
  }

  window.__haPanel = {
    reapply: function () { applyChrome(); report(); },
    swipe: swipe,
    state: getState
  };

  applyChrome();
  setupObservers();
  report();
})();
