(function () {
    'use strict';
    var token = new URLSearchParams(location.search).get('geckoLauncher');
    if (!token) return;
    var base = location.pathname.substring(0, location.pathname.indexOf('/slingbreak/')) + '/bridge/' + token + '/';
    // The game's host API is synchronous (including begin/upload). Batch each frame's
    // playback into one bounded loopback request; no PCM crosses during impacts.
    function audio(method, args) {
        var request = new XMLHttpRequest();
        request.open('POST', base + 'audio/' + method, false);
        request.setRequestHeader('X-Sling-Bridge', '1');
        request.setRequestHeader('Content-Type', 'application/json');
        request.send(JSON.stringify(Array.prototype.slice.call(args)));
        if (request.status !== 200) throw new Error('Native audio HTTP ' + request.status);
        return JSON.parse(request.responseText);
    }
    window.SlingNativeAudio = {};
    ['capabilities','begin','upload','commit','play','playBatch','setState','setMuted',
        'snapshot','uploadMusic','playMusic','musicState','musicFade','release'].forEach(function (method) {
        window.SlingNativeAudio[method] = function () { return audio(method, arguments); };
    });
    function send(method, detail) {
        fetch(base + method + '?detail=' + encodeURIComponent(String(detail || '').substring(0, 2000)), {
            cache: 'no-store', headers: { 'X-Sling-Bridge': '1' }
        }).catch(function () {});
    }
    window.AndroidSlingBreakLauncher = {
        onPageReady: function () { send('onPageReady'); },
        enterGame: function () { send('enterGame'); },
        scriptError: function (detail) { send('scriptError', detail); }
    };
    var stopped = false;
    window.addEventListener('pagehide', function () { stopped = true; });
    function poll() {
        if (stopped) return;
        fetch(base + 'poll', { cache: 'no-store', headers: { 'X-Sling-Bridge': '1' } })
            .then(function (response) {
                if (response.status === 410) { stopped = true; return []; }
                if (!response.ok) throw new Error('Launcher bridge HTTP ' + response.status);
                return response.json();
            }).then(function (scripts) {
                scripts.forEach(function (script) {
                    try { (0, eval)(script); } catch (error) { send('scriptError', error.message); }
                });
            }).catch(function () {}).then(function () { if (!stopped) setTimeout(poll, 100); });
    }
    poll();
}());
