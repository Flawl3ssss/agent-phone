/*
 * Мост терминала. Данные идут в двух направлениях base64-ом: любые байты
 * (включая экранные управляющие последовательности и кавычки) проходят
 * через eval строки в Android без цитирования и без потери байт.
 */
(function () {
  var TermCtor = window.Terminal;
  var screen = document.getElementById('screen');
  if (!TermCtor) {
    screen.textContent = 'xterm не загрузился: проверь assets/terminal в APK';
    return;
  }
  var term = new TermCtor({
    cursorBlink: true,
    fontFamily: 'monospace',
    fontSize: 13,
    theme: { background: '#0d1117', foreground: '#e6edf3', cursor: '#56d364' },
    scrollback: 3000,
  });
  if (window.FitAddon) { term.loadAddon(new window.FitAddon.FitAddon()); }
  term.open(screen);

  var ready = false;
  function post(bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i++) { s += String.fromCharCode(bytes[i]); }
    return btoa(s);
  }
  function sendResize() {
    if (!ready || !term.cols || !term.rows) return;
    Android.resize(term.cols, term.rows);
  }
  term.onData(function (data) {
    var enc = new TextEncoder().encode(data);
    Android.input(post(enc));
  });
  term.onResize(sendResize);

  // Вход из приложения: base64 -> байты -> терминал.
  window.pterm = {
    feed: function (b64) {
      var bin = atob(b64);
      var arr = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) { arr[i] = bin.charCodeAt(i); }
      term.write(arr);
    },
    reset: function () { term.reset(); },
    fit: function () { try { term.fit(); } catch (e) {} sendResize(); },
  };

  // Поворот и мягкая клавиатура меняют высоту вьюпорта: пересчитываем сетку.
  window.addEventListener('resize', function () {
    try { term.fit(); } catch (e) {}
    sendResize();
  });

  ready = true;
  try { term.fit(); } catch (e) {}
  Android.ready(term.cols, term.rows);
})();
