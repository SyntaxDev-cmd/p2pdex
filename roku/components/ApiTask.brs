' Tarefa de rede: painel (config.php) e servidor Xtream/P2P (player_api.php).
' request: { kind: "config" | "login" | "xtream", ... }  ->  response: { ok, data, error, tag }

sub init()
    m.top.functionName = "doWork"
end sub

sub doWork()
    req = m.top.request
    if req = invalid then req = {}
    res = { ok: false, data: invalid, error: "", tag: req.tag }
    kind = StrOf(req.kind)
    if kind = "config" then
        res = loadConfig(res)
    else if kind = "login" then
        res = login(req, res)
    else if kind = "xtream" then
        res = xtream(req, res)
    else
        res.error = "pedido invalido"
    end if
    m.top.response = res
end sub

function newTransfer(url as string, ua as string, port as object) as object
    t = CreateObject("roUrlTransfer")
    t.SetMessagePort(port)
    t.SetUrl(url)
    t.SetCertificatesFile("common:/certs/ca-bundle.crt")
    t.InitClientCertificates()
    t.EnableEncodings(true)
    t.RetainBodyOnError(true)
    if ua = "" then ua = "Mozilla/5.0 (Roku) NovaTV"
    t.AddHeader("User-Agent", ua)
    return t
end function

' GET com tempo limite; tenta 2 vezes. Retorna { code, body }
function httpGet(url as string, ua as string, timeoutMs as integer) as object
    for attempt = 1 to 2
        port = CreateObject("roMessagePort")
        t = newTransfer(url, ua, port)
        if t.AsyncGetToString() then
            msg = wait(timeoutMs, port)
            if type(msg) = "roUrlEvent" then
                code = msg.GetResponseCode()
                if code > 0 then return { code: code, body: msg.GetString() }
            else
                t.AsyncCancel()
            end if
        end if
    end for
    return { code: -1, body: "" }
end function

function loadConfig(res as object) as object
    cfg = AppConfig()
    if cfg.panel_url = "" then
        res.error = "Painel nao configurado (config.json)."
        return res
    end if
    di = CreateObject("roDeviceInfo")
    url = cfg.panel_url + "/api/config.php?key=" + Enc(cfg.panel_key) + "&reseller=" + Enc(cfg.reseller_code)
    url = url + "&platform=tv&model=" + Enc("Roku " + di.GetModelDisplayName()) + "&device_id=" + Enc("roku-" + di.GetChannelClientId())
    url = url + "&version=" + Enc("roku") + "&username=" + Enc(RegRead("user"))
    r = httpGet(url, "", 15000)
    if r.code = 200 then
        data = ParseJson(r.body)
        if data <> invalid then
            RegWrite("config_cache", r.body)
            res.ok = true
            res.data = data
            return res
        end if
    end if
    ' sem internet/painel fora: usa a ultima configuracao salva
    cached = RegRead("config_cache")
    if cached <> "" then
        data = ParseJson(cached)
        if data <> invalid then
            res.ok = true
            res.data = data
            return res
        end if
    end if
    res.error = "Nao consegui falar com o painel (" + StrOf(r.code) + ")."
    return res
end function

function otherScheme(url as string) as string
    if Left(LCase(url), 7) = "http://" then return "https://" + Mid(url, 8)
    if Left(LCase(url), 8) = "https://" then return "http://" + Mid(url, 9)
    return ""
end function

function cleanBase(url as string) as string
    u = url.Trim()
    q = Instr(1, u, "?")
    if q > 0 then u = Left(u, q - 1)
    while Right(u, 1) = "/"
        u = Left(u, Len(u) - 1)
    end while
    for each tail in ["/player_api.php", "/get.php", "/panel_api.php"]
        if LCase(Right(u, Len(tail))) = tail then u = Left(u, Len(u) - Len(tail))
    end for
    if u <> "" and Instr(1, u, "://") = 0 then u = "http://" + u
    return u
end function

' Testa todos os servidores/DNS ao mesmo tempo; vence o primeiro que autenticar.
function login(req as object, res as object) as object
    user = StrOf(req.user).Trim()
    pass = StrOf(req.pass).Trim()
    servers = req.servers
    if servers = invalid then servers = []
    port = CreateObject("roMessagePort")
    jobs = {}
    count = 0
    for each s in servers
        stype = LCase(StrOf(s.type))
        if stype = "" or stype = "xtream" or stype = "p2p" then
            users = [user]
            suffix = StrOf(s.user_suffix)
            if suffix <> "" and Instr(1, user, "@") = 0 then users.Push(user + suffix)
            urls = s.urls
            if urls = invalid then urls = []
            for each raw in urls
                base = cleanBase(StrOf(raw))
                if base <> "" then
                    bases = [base]
                    alt = otherScheme(base)
                    if alt <> "" then bases.Push(alt)
                    for each b in bases
                        for each u in users
                            url = b + "/player_api.php?username=" + Enc(u) + "&password=" + Enc(pass)
                            t = newTransfer(url, StrOf(s.user_agent), port)
                            if t.AsyncGetToString() then
                                jobs[t.GetIdentity().ToStr()] = { t: t, base: b, user: u, server: s }
                                count = count + 1
                            end if
                        end for
                    end for
                end if
            end for
        end if
    end for
    if count = 0 then
        res.error = "Nenhum servidor disponivel no painel."
        return res
    end if
    denied = false
    lastCode = 0
    clock = CreateObject("roTimespan")
    while count > 0 and clock.TotalMilliseconds() < 30000
        msg = wait(1000, port)
        if type(msg) = "roUrlEvent" then
            count = count - 1
            job = jobs[msg.GetSourceIdentity().ToStr()]
            code = msg.GetResponseCode()
            lastCode = code
            if job <> invalid and code = 200 then
                data = ParseJson(msg.GetString())
                if data <> invalid and data.user_info <> invalid then
                    ui = data.user_info
                    auth = StrOf(ui.auth)
                    status = LCase(StrOf(ui.status))
                    if auth = "1" or auth = "true" or (auth = "" and status = "active") then
                        if status = "expired" then
                            res.error = "Assinatura expirada. Renove para continuar."
                            return res
                        end if
                        for each k in jobs
                            jobs[k].t.AsyncCancel()
                        end for
                        res.ok = true
                        res.data = { base: job.base, user: job.user, pass: pass, server: job.server, info: ui }
                        return res
                    end if
                    denied = true
                else if data <> invalid and (data.auth <> invalid or data.error <> invalid) then
                    denied = true
                end if
            end if
        end if
    end while
    if denied then
        res.error = "Usuario ou senha invalidos."
    else
        res.error = "Servidor nao respondeu (" + StrOf(lastCode) + "). Verifique a internet."
    end if
    return res
end function

' Chamada player_api.php?action=...
function xtream(req as object, res as object) as object
    url = StrOf(req.base) + "/player_api.php?username=" + Enc(StrOf(req.user)) + "&password=" + Enc(StrOf(req.pass))
    if StrOf(req.action) <> "" then url = url + "&action=" + StrOf(req.action)
    if req.params <> invalid then
        for each k in req.params
            url = url + "&" + k + "=" + Enc(StrOf(req.params[k]))
        end for
    end if
    r = httpGet(url, StrOf(req.ua), 25000)
    if r.code = 200 then
        data = ParseJson(r.body)
        if data <> invalid then
            res.ok = true
            res.data = data
            return res
        end if
        res.error = "Resposta invalida do servidor."
    else
        res.error = "Servidor nao respondeu (" + StrOf(r.code) + ")."
    end if
    return res
end function
