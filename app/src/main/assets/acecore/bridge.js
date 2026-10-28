/*
 * AceCore Web Runtime bridge. Injected at the top of the game's HTML by WebGameServer.
 * Deliberately ES5 and dependency-free: it must run before any engine code on old and new WebViews alike.
 * There is no JavaScript->Android interface; native code talks to this file only via evaluateJavascript.
 */
(function () {
  'use strict';
  if (window.AceCore) return;
  var cfg = __ACECORE_CONFIG__;
  var A = window.AceCore = { version: 1, errors: [] };

  // ---------------------------------------------------------------- keyboard injection
  var down = {};
  function makeEvent(type, keyCode, key, code) {
    var ev;
    try {
      ev = new KeyboardEvent(type, { key: key, code: code, bubbles: true, cancelable: true, view: window });
    } catch (e) {
      ev = document.createEvent('Event');
      ev.initEvent(type, true, true);
      ev.key = key; ev.code = code;
    }
    // Engines such as RPG Maker MV/MZ and Construct 2 read the legacy keyCode/which fields.
    try { Object.defineProperty(ev, 'keyCode', { get: function () { return keyCode; } }); } catch (e) {}
    try { Object.defineProperty(ev, 'which', { get: function () { return keyCode; } }); } catch (e) {}
    return ev;
  }
  A.key = function (keyCode, key, code, isDown) {
    if (!keyCode && !key) return;
    var id = code || String(keyCode);
    if (isDown && down[id]) return;      // engines implement their own key repeat
    if (!isDown && !down[id]) return;
    down[id] = isDown ? { keyCode: keyCode, key: key, code: code } : null;
    var target = A.keyTarget || (document.activeElement && document.activeElement !== document.body ? document.activeElement : document);
    target.dispatchEvent(makeEvent(isDown ? 'keydown' : 'keyup', keyCode, key, code));
    if (isDown && key && key.length === 1) target.dispatchEvent(makeEvent('keypress', key.charCodeAt(0), key, code));
  };
  A.releaseAll = function () {
    for (var id in down) {
      if (down.hasOwnProperty(id) && down[id]) {
        var k = down[id];
        down[id] = null;
        document.dispatchEvent(makeEvent('keyup', k.keyCode, k.key, k.code));
      }
    }
  };

  // ---------------------------------------------------------------- exit handling
  window.close = function () { location.href = cfg.exitUrl; };

  // ---------------------------------------------------------------- audio lifecycle
  var contexts = [];
  var Orig = window.AudioContext || window.webkitAudioContext;
  if (Orig) {
    var Wrapped = function (opts) {
      var c = opts !== undefined ? new Orig(opts) : new Orig();
      contexts.push(c);
      return c;
    };
    Wrapped.prototype = Orig.prototype;
    window.AudioContext = Wrapped;
    window.webkitAudioContext = Wrapped;
  }
  var pausedMedia = [];
  A.setPaused = function (paused) {
    for (var i = 0; i < contexts.length; i++) {
      try { if (paused) contexts[i].suspend(); else contexts[i].resume(); } catch (e) {}
    }
    if (paused) {
      pausedMedia = [];
      var media = document.querySelectorAll('audio,video');
      for (var j = 0; j < media.length; j++) {
        if (!media[j].paused) { try { media[j].pause(); pausedMedia.push(media[j]); } catch (e) {} }
      }
      A.releaseAll();
    } else {
      for (var k = 0; k < pausedMedia.length; k++) { try { pausedMedia[k].play(); } catch (e) {} }
      pausedMedia = [];
    }
  };
  // Browsers block audio until a user gesture; resume on first touch for engines that created contexts early.
  function unlock() { for (var i = 0; i < contexts.length; i++) { try { if (contexts[i].state === 'suspended') contexts[i].resume(); } catch (e) {} } }
  document.addEventListener('touchend', unlock, true);
  document.addEventListener('keydown', unlock, true);

  // ---------------------------------------------------------------- master volume (RPG Maker MV/MZ mixer)
  A.setVolume = function (v) {
    cfg.volume = v;
    try { if (window.WebAudio && WebAudio.setMasterVolume) WebAudio.setMasterVolume(v); } catch (e) {}
  };
  if (cfg.volume !== 1) {
    var tries = 0;
    var t = setInterval(function () {
      tries++;
      if (window.WebAudio && WebAudio.setMasterVolume) { A.setVolume(cfg.volume); clearInterval(t); }
      else if (tries > 60) clearInterval(t);
    }, 500);
  }

  // ---------------------------------------------------------------- scaling filter
  function addStyle(css) {
    var st = document.createElement('style');
    st.textContent = css;
    (document.head || document.documentElement).appendChild(st);
    return st;
  }
  if (cfg.sharp) addStyle('canvas,img,video{image-rendering:pixelated;image-rendering:crisp-edges;}');
  addStyle('html,body{background:#000;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;}');
  document.addEventListener('contextmenu', function (e) { e.preventDefault(); });

  // ---------------------------------------------------------------- FPS overlay (measured, never estimated)
  var fpsEl = null, fpsOn = false, frames = 0, last = 0;
  function fpsLoop(ts) {
    if (!fpsOn) return;
    frames++;
    if (!last) last = ts;
    if (ts - last >= 1000) {
      var fps = Math.round(frames * 1000 / (ts - last));
      if (fpsEl) fpsEl.textContent = fps + ' FPS · ' + (1000 / Math.max(fps, 1)).toFixed(1) + ' ms';
      frames = 0; last = ts;
    }
    requestAnimationFrame(fpsLoop);
  }
  A.setFps = function (on) {
    fpsOn = !!on;
    if (on) {
      if (!fpsEl) {
        fpsEl = document.createElement('div');
        fpsEl.style.cssText = 'position:fixed;top:6px;left:50%;transform:translateX(-50%);z-index:2147483647;' +
          'font:600 11px monospace;color:#fff;background:rgba(0,0,0,.55);padding:2px 8px;border-radius:9px;pointer-events:none;';
        fpsEl.textContent = '— FPS';
      }
      (document.body || document.documentElement).appendChild(fpsEl);
      frames = 0; last = 0;
      requestAnimationFrame(fpsLoop);
    } else if (fpsEl && fpsEl.parentNode) {
      fpsEl.parentNode.removeChild(fpsEl);
    }
  };
  if (cfg.fps) {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function () { A.setFps(true); });
    else A.setFps(true);
  }

  // ---------------------------------------------------------------- diagnostics
  window.addEventListener('error', function (e) {
    var msg = (e && e.message ? e.message : 'Script error') + (e && e.filename ? ' @ ' + e.filename.replace(location.origin, '') + ':' + e.lineno : '');
    A.errors.push(msg);
    if (A.errors.length > 20) A.errors.shift();
  });
  A.lastErrors = function () { return A.errors.slice(-5).join('\n'); };
})();
