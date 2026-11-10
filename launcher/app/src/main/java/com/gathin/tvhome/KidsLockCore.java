package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The rules of children's mode, with no Android in them so they can be tested: who may lock and unlock, the code, and
 * the page the phone opens. Paths are /k/&lt;token&gt;/ (the page), /state, /lock and /unlock (a JSON body with "pin").
 * A token belongs to one authorised phone; an unknown token gets 404 and nothing else. Unlocking also needs the code,
 * and after MAX_FAILS wrong codes in FAIL_WINDOW_MS every attempt is refused until the window passes.
 */
final class KidsLockCore {
    /** The persisted switch. */
    interface State {
        boolean isLocked();

        String by();

        void set(boolean locked, String by);

        /** The text shown on the lock screen, empty for none; a lock without one clears it. */
        default String note() {
            return "";
        }

        default void setNote(String note) {
        }
    }

    static final class Reply {
        final int status;
        final String type;
        final String body;

        Reply(int status, String type, String body) {
            this.status = status;
            this.type = type;
            this.body = body;
        }
    }

    /** True when a lock set at since (ms, 0 if unknown) is older than maxMs (0 = never expires) at now. */
    static boolean expired(long since, long now, long maxMs) {
        return maxMs > 0 && since > 0 && now - since >= maxMs;
    }

    static final int MAX_FAILS = 5;
    static final long FAIL_WINDOW_MS = 5 * 60 * 1000L;
    private static final int ITERATIONS = 200000;
    /** The name a relay (the home server's page) registers under; it passes on the real phone's name in X-By. */
    static final String RELAY = "iMac";
    private static final Pattern PIN = Pattern.compile("\"pin\"\\s*:\\s*\"([^\"]{0,32})\"");
    private static final Pattern NOTE = Pattern.compile("\"note\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){0,900})\"");
    static final int NOTE_MAX = 140;

    /** The "note" of a lock request's JSON body, unescaped and cut to NOTE_MAX; empty when there is none. */
    static String noteOf(String body) {
        Matcher m = NOTE.matcher(body == null ? "" : body);
        if (!m.find()) return "";
        String s = m.group(1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': case 't': case 'r': out.append(' '); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            out.append('u');
                        }
                    } else {
                        out.append('u');
                    }
                    break;
                default: out.append(n);
            }
        }
        String t = out.toString().replaceAll("\\p{Cntrl}", " ").trim();
        return t.length() > NOTE_MAX ? t.substring(0, NOTE_MAX) : t;
    }

    private final Map<String, String> phones = new LinkedHashMap<>();
    private final String salt, hash;
    private final State state;
    private final LongSupplier clock;
    private final Deque<Long> fails = new ArrayDeque<>();
    private java.util.function.LongFunction<String> usage;
    private com.gathin.tvhome.family.LanPairing pairing;
    private LanActions actions;
    private java.util.function.Supplier<String> health;
    private java.util.function.Supplier<String> screenTime;
    private java.util.function.Supplier<String> licensePlan;
    private java.util.function.Function<String, String> licenseSubmit;
    private static final Pattern KEY = Pattern.compile("\"key\"\\s*:\\s*\"([A-Za-z0-9_.=-]{0,1500})\"");
    private java.util.function.Supplier<String> settingsExport;
    private java.util.function.Predicate<String> settingsRestore;
    private java.util.function.BiConsumer<String, String> events;
    private java.util.function.IntPredicate powerOff;
    private static final Pattern DELAY = Pattern.compile("\"delay_s\"\\s*:\\s*(-?\\d{1,9})");
    private static final Pattern CANCEL = Pattern.compile("\"cancel\"\\s*:\\s*true");
    /** The longest delay a command may carry: a day. */
    static final int MAX_POWER_DELAY_S = 86400;

    /** What the phone can ask of the TV besides the lock: a message over the apps and a pause. */
    interface LanActions {
        /** @return false when the TV cannot show it now (the accessibility helper is off) */
        boolean message(String text);

        boolean pause();
    }

    /** Turns on the paired-phone routes (/ and /api/...): the page, the pairing and the calls that carry a token in a header. */
    void setLan(com.gathin.tvhome.family.LanPairing pairing, LanActions actions) {
        this.pairing = pairing;
        this.actions = actions;
    }

    /** The helper's state for /api/state: a JSON fragment such as "service":true,"journal":[..]; the events (kind, text) go to the journal. */
    void setHealth(java.util.function.Supplier<String> health, java.util.function.BiConsumer<String, String> events) {
        this.health = health;
        this.events = events;
    }

    /** GET /api/settings answers with the launcher's choices (export), POST /api/settings restores them (import, false = refused). */
    void setSettings(java.util.function.Supplier<String> export, java.util.function.Predicate<String> restore) {
        this.settingsExport = export;
        this.settingsRestore = restore;
    }

    /** GET /api/license answers with the plan line, POST /api/license {"key":".."} submits a key (the answer is the plan line, or null when refused). */
    void setLicense(java.util.function.Supplier<String> plan, java.util.function.Function<String, String> submit) {
        this.licensePlan = plan;
        this.licenseSubmit = submit;
    }

    /** Where GET /api/screentime gets the day table (JSON array); without it that path is 404. */
    void setScreenTime(java.util.function.Supplier<String> screenTime) {
        this.screenTime = screenTime;
    }

    /** POST .../poweroff {"delay_s":N} turns the TV off now or in N seconds, {"cancel":true} cancels; false from the predicate = helper off. */
    void setPowerOff(java.util.function.IntPredicate powerOff) {
        this.powerOff = powerOff;
    }

    /** Both routes' work: body {"delay_s":N} (default 0) or {"cancel":true}; by is the parent's name for the journal. */
    private Reply powerOff(String body, String by) {
        if (powerOff == null) return new Reply(404, "application/json", "{}");
        String text = body == null ? "" : body;
        boolean cancel = CANCEL.matcher(text).find();
        int delay = 0;
        Matcher d = DELAY.matcher(text);
        if (!cancel && d.find()) delay = Integer.parseInt(d.group(1));
        else if (!cancel && text.contains("delay_s")) return new Reply(400, "application/json", "{\"error\":\"delay_s must be 0..86400\"}");
        if (!cancel && (delay < 0 || delay > MAX_POWER_DELAY_S)) return new Reply(400, "application/json", "{\"error\":\"delay_s must be 0..86400\"}");
        if (!powerOff.test(cancel ? -1 : delay)) return new Reply(503, "application/json", "{\"error\":\"helper off\"}");
        event(cancel ? "poweroff_cancel" : "poweroff", cancel ? "sleep timer cancelled from " + by
                : delay == 0 ? "TV turned off from " + by : "TV will turn off in " + delay + " s, asked by " + by);
        return new Reply(200, "application/json", "{\"delay_s\":" + (cancel ? -1 : delay) + "}");
    }

    private void event(String kind, String text) {
        if (events != null) events.accept(kind, text);
    }

    /** Where GET /k/token/usage?since=ms gets the screen-time log (UsageLog.json); without it that path is 404. */
    void setUsage(java.util.function.LongFunction<String> usage) {
        this.usage = usage;
    }

    KidsLockCore(Map<String, String> phones, String salt, String hash, State state, LongSupplier clock) {
        this.phones.putAll(phones);
        this.salt = salt;
        this.hash = hash;
        this.state = state;
        this.clock = clock;
    }

    /** The code as the hex PBKDF2-HMAC-SHA256 the theme stores (kids_pin_salt is hex too). */
    static String hashPin(String pin, String saltHex) {
        try {
            byte[] salt = new byte[saltHex.length() / 2];
            for (int i = 0; i < salt.length; i++) salt[i] = (byte) Integer.parseInt(saltHex.substring(2 * i, 2 * i + 2), 16);
            byte[] out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)).getEncoded();
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (GeneralSecurityException | RuntimeException e) {
            return "";
        }
    }

    private String nameOf(String token) {
        String found = null;
        byte[] given = token.getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, String> e : phones.entrySet()) {
            if (MessageDigest.isEqual(given, e.getValue().getBytes(StandardCharsets.UTF_8))) found = e.getKey();
        }
        return found;
    }

    private synchronized boolean pinOk(String pin) {
        long now = clock.getAsLong();
        while (!fails.isEmpty() && now - fails.peekFirst() > FAIL_WINDOW_MS) fails.pollFirst();
        if (fails.size() >= MAX_FAILS) return false;
        boolean ok = MessageDigest.isEqual(hashPin(pin, salt).getBytes(StandardCharsets.UTF_8), hash.getBytes(StandardCharsets.UTF_8));
        if (!ok) fails.addLast(now);
        return ok;
    }

    private static String json(boolean locked, String by) {
        return "{\"locked\":" + locked + ",\"by\":\"" + by.replace("\\", "").replace("\"", "") + "\"}";
    }

    Reply handle(String method, String path, String xBy, String body) {
        return handle(method, path, xBy, body, null);
    }

    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]{0,16})\"");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){0,90})\"");
    private static final Pattern TEXT = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){0,900})\"");

    /** The paired-phone routes: the page at /, POST /api/pair, and /api/... with "Authorization: Bearer token". */
    private Reply handleLan(String method, String path, String authorization, String body) {
        String route = path.split("\\?")[0];
        if (method.equals("GET") && (route.equals("/") || route.equals("/index.html"))) return new Reply(200, "text/html; charset=utf-8", render(LAN_PAGE));
        if (!route.startsWith("/api/")) return new Reply(404, "application/json", "{}");
        if (method.equals("POST") && route.equals("/api/pair")) {
            Matcher c = CODE.matcher(body == null ? "" : body);
            Matcher n = NAME.matcher(body == null ? "" : body);
            String token = c.find() ? pairing.pair(c.group(1), n.find() ? unescape(n.group(1)) : "") : null;
            if (token == null) {
                event("pair_fail", "wrong pairing code from a phone");
                return new Reply(403, "application/json", "{\"error\":\"wrong code\"}");
            }
            event("paired", "a phone was paired");
            return new Reply(200, "application/json", "{\"token\":\"" + token + "\"}");
        }
        String bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7) ? authorization.substring(7).trim() : "";
        com.gathin.tvhome.family.LanPairing.Phone phone = pairing.phoneOf(bearer);
        if (phone == null) return new Reply(401, "application/json", "{\"error\":\"unauthorized\"}");
        String by = phone.name;
        if (method.equals("GET") && route.equals("/api/state")) {
            String j = json(state.isLocked(), state.by());
            return new Reply(200, "application/json", health == null ? j : j.substring(0, j.length() - 1) + "," + health.get() + "}");
        }
        if (route.equals("/api/license") && licensePlan != null) {
            if (method.equals("GET")) return new Reply(200, "application/json", "{\"plan\":\"" + licensePlan.get().replace("\\", "").replace("\"", "") + "\"}");
            if (method.equals("POST")) {
                Matcher k = KEY.matcher(body == null ? "" : body);
                String plan = k.find() ? licenseSubmit.apply(k.group(1)) : null;
                if (plan == null) return new Reply(400, "application/json", "{\"error\":\"key not accepted\"}");
                event("license", "a licence key was entered from " + by);
                return new Reply(200, "application/json", "{\"plan\":\"" + plan.replace("\\", "").replace("\"", "") + "\"}");
            }
        }
        if (route.equals("/api/settings") && settingsExport != null) {
            if (method.equals("GET")) return new Reply(200, "application/json", settingsExport.get());
            if (method.equals("POST")) {
                boolean ok = settingsRestore.test(body == null ? "" : body);
                if (ok) event("settings_restored", "settings restored from " + by);
                return ok ? new Reply(200, "application/json", "{}") : new Reply(400, "application/json", "{\"error\":\"not a settings backup\"}");
            }
        }
        if (method.equals("GET") && route.equals("/api/screentime") && screenTime != null) {
            return new Reply(200, "application/json", "{\"days\":" + screenTime.get() + "}");
        }
        if (method.equals("GET") && route.equals("/api/usage") && usage != null) {
            return new Reply(200, "application/json", usage.apply(UsageLogCore.sinceOf(path)));
        }
        if (method.equals("POST") && route.equals("/api/lock")) {
            state.setNote(noteOf(body));
            state.set(true, by);
            return new Reply(200, "application/json", json(true, by));
        }
        if (method.equals("POST") && route.equals("/api/unlock")) {
            Matcher m = PIN.matcher(body == null ? "" : body);
            if (!m.find() || !pinOk(m.group(1))) {
                event("unlock_fail", "wrong code to unlock from " + by);
                return new Reply(403, "application/json", "{\"error\":\"wrong code\"}");
            }
            state.set(false, by);
            return new Reply(200, "application/json", json(false, by));
        }
        if (method.equals("POST") && route.equals("/api/message") && actions != null) {
            Matcher t = TEXT.matcher(body == null ? "" : body);
            String text = t.find() ? noteOf("{\"note\":\"" + t.group(1) + "\"}") : "";
            if (text.isEmpty()) return new Reply(400, "application/json", "{\"error\":\"empty\"}");
            return actions.message(text) ? new Reply(200, "application/json", "{}") : new Reply(503, "application/json", "{\"error\":\"helper off\"}");
        }
        if (method.equals("POST") && route.equals("/api/poweroff")) return powerOff(body, by);
        if (method.equals("POST") && route.equals("/api/pause") && actions != null) {
            return actions.pause() ? new Reply(200, "application/json", "{}") : new Reply(503, "application/json", "{\"error\":\"helper off\"}");
        }
        return new Reply(404, "application/json", "{}");
    }

    private static String unescape(String s) {
        return noteOf("{\"note\":\"" + s + "\"}");
    }

    /** @param authorization the Authorization header, null when there is none */
    Reply handle(String method, String path, String xBy, String body, String authorization) {
        if (pairing != null && !path.startsWith("/k/")) return handleLan(method, path, authorization, body);
        String[] parts = path.split("\\?")[0].split("/");
        // "", "k", token, what
        if (parts.length < 3 || !parts[1].equals("k")) return new Reply(404, "application/json", "{}");
        String name = nameOf(parts[2]);
        if (name == null) return new Reply(404, "application/json", "{}");
        String what = parts.length > 3 ? parts[3] : "";
        String by = name.equals(RELAY) && xBy != null && !xBy.trim().isEmpty() ? xBy.trim() : name;
        if (by.length() > 30) by = by.substring(0, 30);
        if (method.equals("GET") && what.isEmpty()) return new Reply(200, "text/html; charset=utf-8", render(PAGE));
        if (method.equals("GET") && what.equals("state")) return new Reply(200, "application/json", json(state.isLocked(), state.by()));
        if (method.equals("GET") && what.equals("usage") && usage != null) {
            return new Reply(200, "application/json", usage.apply(UsageLogCore.sinceOf(path)));
        }
        if (method.equals("POST") && what.equals("poweroff")) return powerOff(body, by);
        if (method.equals("POST") && what.equals("lock")) {
            state.setNote(noteOf(body));
            state.set(true, by);
            return new Reply(200, "application/json", json(true, by));
        }
        if (method.equals("POST") && what.equals("unlock")) {
            Matcher m = PIN.matcher(body == null ? "" : body);
            if (!m.find() || !pinOk(m.group(1))) return new Reply(403, "application/json", "{\"error\":\"wrong code\"}");
            state.set(false, by);
            return new Reply(200, "application/json", json(false, by));
        }
        return new Reply(404, "application/json", "{}");
    }

    /** Marks where render() puts the S table and the code that fills the page from it. */
    private static final String I18N = "@I18N@";

    /**
     * The two phone pages are written once, without any language: their words come from the string resources
     * (web_*), handed over as the S table at the top of the script, and elements marked data-t (text) or data-p
     * (placeholder) are filled from it when the page loads. See setText.
     */
    private static final String LAN_PAGE = "<!doctype html><html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title></title><style>:root{color-scheme:light dark}body{margin:0;font:18px/1.4 system-ui,sans-serif;background:#0d0f14;color:#f2f3f7}"
            + "main{max-width:480px;margin:0 auto;padding:28px 18px}h1{font-size:24px;margin:0 0 6px}small{color:#99a}"
            + "input,button{width:100%;box-sizing:border-box;padding:16px;font:inherit;border:0;border-radius:14px;margin-top:12px}"
            + "input{background:#1a1d26;color:inherit}button{font-weight:600;color:#fff;background:#3a4a63}#e{color:#ff7a6b;min-height:1.4em;margin-top:8px}"
            + "pre{white-space:pre-wrap;color:#99a;font-size:14px}</style><main><h1 id=s>…</h1><small id=b></small>"
            + "<div id=pair style=\"display:none\"><input id=c inputmode=numeric data-p=web_pair_code_hint><input id=n data-p=web_pair_name_hint><button id=pg data-t=web_pair_button></button></div>"
            + "<div id=ctl style=\"display:none\"><button id=g></button><input id=p type=password inputmode=numeric data-p=web_unlock_hint style=\"display:none\">"
            + "<input id=m maxlength=140 data-p=web_message_hint><button id=sm data-t=web_send_message></button><button id=ps data-t=web_pause></button><button id=po data-t=web_power_off></button><button id=pt data-t=web_power_off_30></button><button id=us data-t=web_screentime></button><button id=bk data-t=web_backup></button><button id=rs data-t=web_restore></button><input id=rf type=file accept=.json style=\"display:none\"><input id=lk data-p=web_license_hint><button id=lb data-t=web_license_button></button><pre id=u></pre><pre id=jl></pre></div>"
            + "<div id=e></div></main><script>" + I18N + "document.title=S.web_title;"
            + "let T=null,L=false;try{T=localStorage.getItem('t')}catch(x){}"
            + "const q=new URLSearchParams(location.search),$=i=>document.getElementById(i),by=n=>({auto:S.web_by_auto,reset:S.web_by_reset,cloud:S.web_by_cloud})[n]||n;"
            + "async function api(m,u,b){const r=await fetch('/api/'+u,{method:m,headers:{'Authorization':'Bearer '+T},body:b?JSON.stringify(b):undefined});"
            + "if(r.status===401){T=null;try{localStorage.removeItem('t')}catch(x){}show()}return r}"
            + "function show(){$('pair').style.display=T?'none':'block';$('ctl').style.display=T?'block':'none';if(!T){$('s').textContent=S.web_connect_phone;if(q.get('c'))$('c').value=q.get('c')}else st()}"
            + "async function st(){try{const j=await (await api('GET','state')).json();L=j.locked;$('s').textContent=L?S.web_locked:S.web_unlocked;"
            + "$('b').textContent=j.service===false?S.web_service_off:L&&j.by?S.web_locked_by.replace('{0}',by(j.by)):'';$('jl').textContent=(j.journal||[]).slice(0,8).map(e=>new Date(e.t).toLocaleString(S.web_locale,{day:'numeric',month:'short',hour:'2-digit',minute:'2-digit'})+' '+({service_off:S.web_j_service_off,service_on:S.web_j_service_on,pin_fail:S.web_j_pin_fail,unlock_fail:S.web_j_unlock_fail,pair_fail:S.web_j_pair_fail,paired:S.web_j_paired,poweroff:S.web_j_poweroff,poweroff_cancel:S.web_j_poweroff_cancel}[e.kind]||e.kind)).join('\\n');$('g').textContent=L?S.web_unlock:S.web_lock;$('g').style.background=L?'#2e9d57':'#c0392b';if(!L)$('p').style.display='none'}catch(x){$('s').textContent=S.web_no_answer}}"
            + "$('pg').onclick=async()=>{$('e').textContent='';const r=await fetch('/api/pair',{method:'POST',body:JSON.stringify({code:$('c').value.trim(),name:$('n').value.trim()||S.web_phone_name})});"
            + "if(!r.ok){$('e').textContent=S.web_pair_bad;return}T=(await r.json()).token;try{localStorage.setItem('t',T)}catch(x){}history.replaceState(null,'','/');show()};"
            + "$('g').onclick=async()=>{$('e').textContent='';if(!L){await api('POST','lock',{});return st()}if($('p').style.display==='none'){$('p').style.display='block';$('p').focus();return}"
            + "const r=await api('POST','unlock',{pin:$('p').value});if(r.ok){$('p').value='';$('p').style.display='none'}else $('e').textContent=S.web_wrong_pin;st()};"
            + "$('sm').onclick=async()=>{const t=$('m').value.trim();if(!t)return;const r=await api('POST','message',{text:t});$('e').textContent=r.ok?'':S.web_not_sent;if(r.ok)$('m').value=''};"
            + "$('ps').onclick=()=>api('POST','pause',{});$('po').onclick=async()=>{if(confirm(S.web_confirm_off))$('e').textContent=(await api('POST','poweroff',{delay_s:0})).ok?'':S.web_off_failed};$('pt').onclick=async()=>{$('e').textContent=(await api('POST','poweroff',{delay_s:1800})).ok?S.web_off_in_30:S.web_off_failed};"
            + "$('bk').onclick=async()=>{const t=await (await api('GET','settings')).text();const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([t],{type:'application/json'}));a.download='tv-settings.json';a.click()};"
            + "$('lb').onclick=async()=>{const r=await api('POST','license',{key:$('lk').value.trim()});$('e').textContent=r.ok?(await r.json()).plan:S.web_license_bad;if(r.ok)$('lk').value=''};"
            + "$('rs').onclick=()=>$('rf').click();$('rf').onchange=async()=>{const f=$('rf').files[0];if(!f)return;const r=await fetch('/api/settings',{method:'POST',headers:{'Authorization':'Bearer '+T},body:await f.text()});$('e').textContent=r.ok?S.web_restored:S.web_not_a_backup;$('rf').value=''};"
            + "$('us').onclick=async()=>{const j=await (await api('GET','screentime')).json();const f=s=>{const m=Math.floor(s/60);return m<60?m+' '+S.web_min:Math.floor(m/60)+' '+S.web_hour+' '+('0'+m%60).slice(-2)+' '+S.web_min};$('u').textContent=(j.days||[]).slice(-7).reverse().map(d=>new Date(d.day+'T12:00').toLocaleDateString(S.web_locale,{weekday:'short',day:'numeric',month:'short'})+': '+f(d.s)+(d.apps.length?'  ('+d.apps.slice(0,3).map(a=>a.l+' '+f(a.s)).join(', ')+')':'')).join('\\n')};"
            + "show();setInterval(()=>{if(T)st()},10000)</script></html>";

    private static final String PAGE = "<!doctype html><html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title></title><style>:root{color-scheme:light dark}body{margin:0;font:18px/1.4 system-ui,sans-serif;background:#0d0f14;color:#f2f3f7}"
            + "main{max-width:480px;margin:0 auto;padding:28px 18px}h1{font-size:24px;margin:0 0 6px}small{color:#99a}"
            + "input,button{width:100%;box-sizing:border-box;padding:16px;font:inherit;border:0;border-radius:14px;margin-top:14px}"
            + "input{background:#1a1d26;color:inherit}button{font-weight:600;color:#fff}#e{color:#ff7a6b;min-height:1.4em;margin-top:8px}</style>"
            + "<main><h1 id=s>…</h1><small id=b></small><input id=p type=password inputmode=numeric data-p=web_unlock_hint style=\"display:none\">"
            + "<button id=g></button><div id=e></div></main><script>" + I18N + "document.title=S.web_kids_title;"
            + "const u=location.pathname.replace(/\\/$/,''),by=n=>({auto:S.web_by_auto,reset:S.web_by_reset,cloud:S.web_by_cloud})[n]||n;let L=false;"
            + "async function r(){try{const j=await (await fetch(u+'/state')).json();L=j.locked;s.textContent=L?S.web_kids_on:S.web_unlocked;"
            + "b.textContent=L&&j.by?S.web_locked_by.replace('{0}',by(j.by)):'';g.textContent=L?S.web_unlock:S.web_lock;g.style.background=L?'#2e9d57':'#c0392b';"
            + "if(!L)p.style.display='none'}catch(x){s.textContent=S.web_no_answer}}"
            + "g.onclick=async()=>{e.textContent='';if(!L){await fetch(u+'/lock',{method:'POST'});return r()}"
            + "if(p.style.display==='none'){p.style.display='block';p.focus();return}"
            + "const x=await fetch(u+'/unlock',{method:'POST',body:JSON.stringify({pin:p.value})});"
            + "if(x.ok){p.value='';p.style.display='none'}else e.textContent=S.web_wrong_pin;r()};"
            + "r();setInterval(r,10000)</script></html>";

    private Supplier<Map<String, String>> text = Collections::emptyMap;

    /**
     * Where the phone pages get their words: a table of web_* resource names to text in the TV's language. The pages
     * carry no language of their own, so without it they show the names.
     */
    void setText(Supplier<Map<String, String>> text) {
        this.text = text;
    }

    private String render(String page) {
        StringBuilder table = new StringBuilder("const S={");
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(text.get()).entrySet()) {
            if (!first) table.append(',');
            first = false;
            table.append(js(e.getKey())).append(':').append(js(e.getValue()));
        }
        table.append("};if(!S.web_locale)S.web_locale='en';document.documentElement.lang=S.web_locale;"
                + "document.querySelectorAll('[data-t]').forEach(e=>e.textContent=S[e.dataset.t]||e.dataset.t);"
                + "document.querySelectorAll('[data-p]').forEach(e=>e.placeholder=S[e.dataset.p]||e.dataset.p);");
        return page.replace(I18N, table.toString());
    }

    /** A JavaScript string literal for text placed inside a script element. */
    private static String js(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == ' ' || c == ' ') out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
