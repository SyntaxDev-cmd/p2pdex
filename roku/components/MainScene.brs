' NovaTV para Roku - tela principal
' Menu (TV/Filmes/Series/Favoritos/Configuracoes) | categorias | itens.
' Player com troca automatica de formato (HLS/TS/URL antiga) e vigia de "carregando eternamente".

sub init()
    m.cfg = AppConfig()
    m.menu = m.top.FindNode("menu")
    m.cats = m.top.FindNode("cats")
    m.items = m.top.FindNode("items")
    m.grid = m.top.FindNode("grid")
    m.status = m.top.FindNode("status")
    m.itemsTitle = m.top.FindNode("itemsTitle")
    m.catsTitle = m.top.FindNode("catsTitle")
    m.home = m.top.FindNode("home")
    m.loginBox = m.top.FindNode("loginBox")
    m.loginList = m.top.FindNode("loginList")
    m.loginMsg = m.top.FindNode("loginMsg")
    m.loading = m.top.FindNode("loading")
    m.video = m.top.FindNode("video")
    m.osd = m.top.FindNode("osd")
    m.stallTimer = m.top.FindNode("stallTimer")
    m.osdTimer = m.top.FindNode("osdTimer")
    m.clockTimer = m.top.FindNode("clockTimer")
    m.top.FindNode("title").text = m.cfg.app_name

    m.tasks = {}
    m.cache = {}
    m.mode = ""
    m.catList = []
    m.itemList = []
    m.liveList = []
    m.series = invalid
    m.panel = {}
    m.login = invalid
    m.prefVariant = {}

    accent = m.cfg.accent
    if Left(accent, 1) = "#" then accent = "0x" + Mid(accent, 2) + "FF"
    for each l in [m.menu, m.cats, m.items, m.loginList]
        l.focusBitmapBlendColor = accent
    end for

    m.menu.ObserveField("itemSelected", "onMenu")
    m.menu.ObserveField("itemFocused", "onMenu")
    m.cats.ObserveField("itemSelected", "onCategory")
    m.items.ObserveField("itemSelected", "onItem")
    m.grid.ObserveField("itemSelected", "onGridItem")
    m.loginList.ObserveField("itemSelected", "onLoginItem")
    m.video.ObserveField("state", "onVideoState")
    m.stallTimer.ObserveField("fire", "onStall")
    m.osdTimer.ObserveField("fire", "hideOsd")
    m.clockTimer.ObserveField("fire", "updateClock")
    m.clockTimer.control = "start"
    updateClock()

    m.typedUser = RegRead("user")
    m.typedPass = ""
    callApi({ kind: "config", tag: "config" })
end sub

sub updateClock()
    dt = CreateObject("roDateTime")
    dt.ToLocalTime()
    h = dt.GetHours().ToStr()
    mi = dt.GetMinutes().ToStr()
    if Len(h) < 2 then h = "0" + h
    if Len(mi) < 2 then mi = "0" + mi
    m.top.FindNode("clock").text = h + ":" + mi
end sub

' ---------------------------------------------------------------- rede
sub callApi(req as object)
    t = CreateObject("roSGNode", "ApiTask")
    t.ObserveField("response", "onApi")
    t.request = req
    m.tasks[req.tag] = t
    t.control = "RUN"
end sub

function xreq(tag as string, action as string, params as object) as object
    return { kind: "xtream", tag: tag, base: m.login.base, user: m.login.user, pass: m.login.pass, action: action, params: params, ua: StrOf(m.login.server.user_agent) }
end function

sub onApi(ev as object)
    res = ev.GetData()
    if res = invalid then return
    tag = StrOf(res.tag)
    m.tasks.Delete(tag)
    if tag = "config" then
        onConfig(res)
    else if tag = "login" then
        onLogin(res)
    else if Left(tag, 5) = "cats:" then
        onCats(res, Mid(tag, 6))
    else if Left(tag, 6) = "items:" then
        onItems(res, Mid(tag, 7))
    else if tag = "series" then
        onSeriesInfo(res)
    end if
end sub

' ---------------------------------------------------------------- painel e login
sub onConfig(res as object)
    if res.ok then m.panel = res.data
    saved = RegRead("login")
    if saved <> "" then
        lg = ParseJson(saved)
        if lg <> invalid and lg.base <> invalid then
            m.login = lg
            showHome()
            return
        end if
    end if
    if not res.ok then
        showLogin(res.error)
    else
        showLogin("")
    end if
end sub

sub showLogin(msg as string)
    m.loading.visible = false
    m.home.visible = false
    m.loginBox.visible = true
    if msg <> "" then m.loginMsg.text = msg
    refreshLoginList()
    m.loginList.SetFocus(true)
end sub

sub refreshLoginList()
    u = m.typedUser
    if u = "" then u = "(toque para digitar)"
    p = "(toque para digitar)"
    if m.typedPass <> "" then p = String(Len(m.typedPass), "*")
    c = CreateObject("roSGNode", "ContentNode")
    for each t in ["Usuario:  " + u, "Senha:  " + p, ">>  ENTRAR"]
        n = c.CreateChild("ContentNode")
        n.title = t
    end for
    m.loginList.content = c
end sub

sub onLoginItem()
    i = m.loginList.itemSelected
    if i = 0 then
        askText("Usuario", m.typedUser, "user")
    else if i = 1 then
        askText("Senha", "", "pass")
    else
        doLogin()
    end if
end sub

sub askText(title as string, value as string, target as string)
    d = CreateObject("roSGNode", "StandardKeyboardDialog")
    d.title = title
    d.text = value
    d.buttons = ["OK", "Cancelar"]
    d.ObserveField("buttonSelected", "onTextDialog")
    d.ObserveField("wasClosed", "onDialogClosed")
    m.textTarget = target
    m.top.dialog = d
end sub

sub onTextDialog()
    d = m.top.dialog
    if d = invalid then return
    if d.buttonSelected = 0 then
        txt = StrOf(d.text).Trim()
        if m.textTarget = "user" then
            m.typedUser = txt
        else if m.textTarget = "pass" then
            m.typedPass = txt
        else if m.textTarget = "pin" then
            checkPin(txt)
        end if
    end if
    d.close = true
    if m.loginBox.visible then
        refreshLoginList()
        if m.textTarget = "user" and m.typedPass = "" then m.loginList.jumpToItem = 1
    end if
end sub

sub onDialogClosed()
    restoreFocus()
end sub

sub doLogin()
    if m.typedUser = "" or m.typedPass = "" then
        m.loginMsg.text = "Preencha usuario e senha."
        return
    end if
    servers = []
    if m.panel <> invalid and m.panel.servers <> invalid then servers = m.panel.servers
    if servers.Count() = 0 then
        m.loginMsg.text = "O painel nao enviou servidores. Verifique a internet e tente de novo."
        callApi({ kind: "config", tag: "config" })
        return
    end if
    m.loginMsg.text = "Conectando ao melhor servidor..."
    callApi({ kind: "login", tag: "login", user: m.typedUser, pass: m.typedPass, servers: servers })
end sub

sub onLogin(res as object)
    if not res.ok then
        m.loginMsg.text = res.error
        return
    end if
    m.login = res.data
    RegWrite("user", m.typedUser)
    RegWrite("login", FormatJson(m.login))
    m.loginBox.visible = false
    showHome()
end sub

' ---------------------------------------------------------------- tela inicial
sub showHome()
    m.loading.visible = false
    m.loginBox.visible = false
    m.home.visible = true
    mode = LCase(m.cfg.app_mode)
    m.menuIds = []
    labels = []
    if mode <> "sports" then
        m.menuIds = ["live", "movie", "series", "fav", "settings"]
        labels = ["TV ao Vivo", "Filmes", "Series", "Favoritos", "Configuracoes"]
    else
        m.menuIds = ["live", "fav", "settings"]
        labels = ["Canais de Esporte", "Favoritos", "Configuracoes"]
    end if
    if mode = "kids" then labels[0] = "Canais Infantis"
    c = CreateObject("roSGNode", "ContentNode")
    for each t in labels
        n = c.CreateChild("ContentNode")
        n.title = t
    end for
    m.menu.content = c
    m.menu.SetFocus(true)
    m.focusArea = "menu"
    openMode(m.menuIds[0])
end sub

sub onMenu()
    if m.menuIds = invalid then return
    i = m.menu.itemFocused
    if i < 0 or i >= m.menuIds.Count() then return
    id = m.menuIds[i]
    if id <> m.mode then openMode(id)
end sub

sub openMode(id as string)
    m.mode = id
    m.status.text = ""
    m.series = invalid
    m.cats.content = CreateObject("roSGNode", "ContentNode")
    m.items.content = CreateObject("roSGNode", "ContentNode")
    m.grid.content = CreateObject("roSGNode", "ContentNode")
    m.items.visible = true
    m.grid.visible = false
    m.itemsTitle.text = ""
    if id = "fav" then
        m.catsTitle.text = ""
        showFavorites()
    else if id = "settings" then
        m.catsTitle.text = ""
        showSettings()
    else
        m.catsTitle.text = "CATEGORIAS"
        action = "get_live_categories"
        if id = "movie" then action = "get_vod_categories"
        if id = "series" then action = "get_series_categories"
        key = "cats:" + id
        if m.cache[key] <> invalid then
            showCats(m.cache[key])
        else
            m.status.text = "Carregando categorias..."
            callApi(xreq(key, action, {}))
        end if
    end if
end sub

function filters() as object
    f = {}
    if m.panel <> invalid and m.panel.filters <> invalid then f = m.panel.filters
    return f
end function

sub onCats(res as object, id as string)
    if id <> m.mode then return
    if not res.ok then
        m.status.text = res.error + "  (OK em uma categoria para tentar de novo)"
        return
    end if
    f = filters()
    mode = LCase(m.cfg.app_mode)
    kidsW = f.kids
    if kidsW = invalid then kidsW = ["kids", "infantil", "desenho", "cartoon", "disney", "nick", "gloob", "animacao"]
    sportsW = f.sports
    if sportsW = invalid then sportsW = ["esporte", "sport", "futebol", "premiere", "espn", "sportv", "combate", "ufc"]
    adultW = f.adult
    if adultW = invalid then adultW = ["adult", "xxx", "+18", "18+", "adulto"]
    showAdult = RegRead("adult") = "1" and mode <> "kids"
    list = []
    if type(res.data) = "roArray" then
        for each c in res.data
            name = StrOf(c.category_name)
            ok = true
            if not showAdult and Matches(name, adultW) then ok = false
            if mode = "kids" and not Matches(name, kidsW) then ok = false
            if mode = "kids" and Matches(name, sportsW) then ok = false
            if mode = "sports" and not Matches(name, sportsW) then ok = false
            if ok then list.Push({ id: StrOf(c.category_id), name: name })
        end for
    end if
    m.cache["cats:" + id] = list
    showCats(list)
end sub

sub showCats(list as object)
    m.catList = list
    c = CreateObject("roSGNode", "ContentNode")
    for each it in list
        n = c.CreateChild("ContentNode")
        n.title = it.name
    end for
    m.cats.content = c
    if list.Count() = 0 then
        m.status.text = "Nenhuma categoria encontrada."
    else
        m.status.text = "Escolha uma categoria."
    end if
end sub

sub onCategory()
    i = m.cats.itemSelected
    if m.mode = "live" or m.mode = "movie" or m.mode = "series" then
        if m.catList.Count() = 0 then
            openMode(m.mode)
            return
        end if
        if i < 0 or i >= m.catList.Count() then return
        cat = m.catList[i]
        m.series = invalid
        m.itemsTitle.text = UCase(cat.name)
        key = "items:" + m.mode + ":" + cat.id
        if m.cache[key] <> invalid then
            showItems(m.cache[key])
            return
        end if
        action = "get_live_streams"
        if m.mode = "movie" then action = "get_vod_streams"
        if m.mode = "series" then action = "get_series"
        m.status.text = "Carregando..."
        callApi(xreq(key, action, { category_id: cat.id }))
    end if
end sub

sub onItems(res as object, key as string)
    parts = key.Split(":")
    if parts.Count() < 2 or parts[0] <> m.mode then return
    if not res.ok then
        m.status.text = res.error + "  (OK na categoria para tentar de novo)"
        return
    end if
    list = []
    if type(res.data) = "roArray" then
        for each s in res.data
            if m.mode = "series" then
                list.Push({ kind: "series", id: StrOf(s.series_id), name: StrOf(s.name), icon: StrOf(s.cover), ext: "" })
            else if m.mode = "movie" then
                list.Push({ kind: "movie", id: StrOf(s.stream_id), name: StrOf(s.name), icon: StrOf(s.stream_icon), ext: StrOf(s.container_extension) })
            else
                list.Push({ kind: "live", id: StrOf(s.stream_id), name: StrOf(s.name), icon: StrOf(s.stream_icon), ext: "" })
            end if
            ' listas enormes deixam o Roku lento: mostra as primeiras 600
            if list.Count() >= 600 then exit for
        end for
    end if
    m.cache["items:" + key] = list
    showItems(list)
end sub

sub showItems(list as object)
    m.itemList = list
    m.status.text = ""
    if list.Count() = 0 then m.status.text = "Nada nesta categoria."
    if m.mode = "live" or m.mode = "fav" or m.series <> invalid then
        m.grid.visible = false
        m.items.visible = true
        c = CreateObject("roSGNode", "ContentNode")
        n0 = 1
        for each it in list
            n = c.CreateChild("ContentNode")
            prefix = ""
            if m.mode = "live" then prefix = n0.ToStr() + "   "
            if m.mode = "fav" then
                if it.kind = "live" then prefix = "[TV]  "
                if it.kind = "movie" then prefix = "[Filme]  "
                if it.kind = "series" then prefix = "[Serie]  "
            end if
            n.title = prefix + it.name
            n0 = n0 + 1
        end for
        m.items.content = c
        if m.mode = "live" or m.mode = "fav" then m.liveList = list
    else
        m.items.visible = false
        m.grid.visible = true
        c = CreateObject("roSGNode", "ContentNode")
        for each it in list
            n = c.CreateChild("ContentNode")
            n.ShortDescriptionLine1 = it.name
            n.HDPosterUrl = it.icon
            n.SDPosterUrl = it.icon
        end for
        m.grid.content = c
    end if
end sub

sub onGridItem()
    i = m.grid.itemSelected
    if i < 0 or i >= m.itemList.Count() then return
    openItem(m.itemList[i], i)
end sub

sub onItem()
    i = m.items.itemSelected
    if m.mode = "settings" then
        onSetting(i)
        return
    end if
    if i < 0 or i >= m.itemList.Count() then return
    openItem(m.itemList[i], i)
end sub

sub openItem(it as object, index as integer)
    if it.kind = "series" then
        m.series = it
        m.itemsTitle.text = UCase(it.name) + "  -  EPISODIOS"
        m.status.text = "Carregando episodios..."
        callApi(xreq("series", "get_series_info", { series_id: it.id }))
    else if it.kind = "episode" then
        playItem(it, index)
    else
        playItem(it, index)
    end if
end sub

sub onSeriesInfo(res as object)
    if m.series = invalid then return
    if not res.ok then
        m.status.text = res.error
        return
    end if
    list = []
    eps = res.data.episodes
    if eps <> invalid then
        seasons = []
        if type(eps) = "roAssociativeArray" then
            for each k in eps
                seasons.Push(k)
            end for
            seasons.Sort()
            for each k in seasons
                for each e in eps[k]
                    list.Push({ kind: "episode", id: StrOf(e.id), name: "T" + StrOf(e.season) + " E" + StrOf(e.episode_num) + "  " + StrOf(e.title), icon: "", ext: StrOf(e.container_extension) })
                end for
            end for
        else if type(eps) = "roArray" then
            for each season in eps
                if type(season) = "roArray" then
                    for each e in season
                        list.Push({ kind: "episode", id: StrOf(e.id), name: "T" + StrOf(e.season) + " E" + StrOf(e.episode_num) + "  " + StrOf(e.title), icon: "", ext: StrOf(e.container_extension) })
                    end for
                end if
            end for
        end if
    end if
    showItems(list)
    m.items.SetFocus(true)
    m.focusArea = "items"
end sub

' ---------------------------------------------------------------- favoritos
function favList() as object
    raw = RegRead("favs")
    list = invalid
    if raw <> "" then list = ParseJson(raw)
    if list = invalid then list = []
    return list
end function

sub toggleFavorite()
    it = invalid
    if m.focusArea = "items" and m.items.visible then
        i = m.items.itemFocused
        if i >= 0 and i < m.itemList.Count() then it = m.itemList[i]
    else if m.focusArea = "items" and m.grid.visible then
        i = m.grid.itemFocused
        if i >= 0 and i < m.itemList.Count() then it = m.itemList[i]
    end if
    if it = invalid or it.kind = "episode" then return
    list = favList()
    out = []
    found = false
    for each f in list
        if f.kind = it.kind and f.id = it.id then
            found = true
        else
            out.Push(f)
        end if
    end for
    if not found then out.Unshift(it)
    RegWrite("favs", FormatJson(out))
    if found then
        showOsd("Removido dos favoritos")
    else
        showOsd("Adicionado aos favoritos")
    end if
    if m.mode = "fav" then showFavorites()
end sub

sub showFavorites()
    m.itemsTitle.text = "FAVORITOS"
    list = favList()
    showItems(list)
    if list.Count() = 0 then m.status.text = "Aperte * (opcoes) em um canal, filme ou serie para favoritar."
end sub

' ---------------------------------------------------------------- configuracoes
sub showSettings()
    m.itemsTitle.text = "CONFIGURACOES"
    fmt = RegRead("format", "auto")
    fmtName = "Automatico (HLS primeiro, troca sozinho)"
    if fmt = "hls" then fmtName = "HLS (.m3u8) - melhor em internet lenta"
    if fmt = "ts" then fmtName = "MPEG-TS (.ts)"
    pl = RegRead("player", "normal")
    plName = "Padrao (qualidade maxima)"
    if pl = "compat" then plName = "Compativel (ate 720p, internet lenta/aparelho simples)"
    ad = "Oculto"
    if RegRead("adult") = "1" then ad = "Visivel"
    user = ""
    if m.login <> invalid then user = StrOf(m.login.user)
    m.settingIds = ["format", "player", "adult", "logout", "about"]
    labels = ["Formato da lista: " + fmtName, "Player: " + plName, "Conteudo adulto: " + ad, "Trocar de conta / sair (" + user + ")", "Sobre: " + m.cfg.app_name + " para Roku"]
    if LCase(m.cfg.app_mode) = "kids" then
        m.settingIds = ["format", "player", "logout", "about"]
        labels = [labels[0], labels[1], labels[3], labels[4]]
    end if
    m.grid.visible = false
    m.items.visible = true
    c = CreateObject("roSGNode", "ContentNode")
    for each t in labels
        n = c.CreateChild("ContentNode")
        n.title = t
    end for
    m.items.content = c
    m.status.text = "OK para mudar. O formato e o player valem para todos os canais."
end sub

sub onSetting(i as integer)
    if i < 0 or i >= m.settingIds.Count() then return
    id = m.settingIds[i]
    if id = "format" then
        cur = RegRead("format", "auto")
        nxt = "hls"
        if cur = "hls" then nxt = "ts"
        if cur = "ts" then nxt = "auto"
        RegWrite("format", nxt)
        m.prefVariant = {}
    else if id = "player" then
        if RegRead("player", "normal") = "compat" then
            RegWrite("player", "normal")
        else
            RegWrite("player", "compat")
        end if
    else if id = "adult" then
        if RegRead("adult") = "1" then
            RegWrite("adult", "0")
            m.cache = {}
        else
            askText("PIN para liberar adultos (padrao 0000)", "", "pin")
            return
        end if
    else if id = "logout" then
        RegDelete("login")
        m.login = invalid
        m.cache = {}
        m.typedPass = ""
        showLogin("Entre com outra conta.")
        return
    end if
    showSettings()
    m.items.jumpToItem = i
end sub

sub checkPin(txt as string)
    if txt = RegRead("pin", "0000") then
        RegWrite("adult", "1")
        m.cache = {}
        showOsd("Conteudo adulto liberado")
    else
        showOsd("PIN incorreto")
    end if
    if m.mode = "settings" then showSettings()
end sub

' ---------------------------------------------------------------- player
function buildUrl(kind as string, id as string, ext as string) as string
    s = m.login.server
    b = m.login.base
    lib = cleanStreamBase(StrOf(s.stream_base))
    if lib <> "" then b = lib
    tpl = StrOf(s.stream_template)
    if tpl = "" then tpl = "{base}/{kind}/{user}/{pass}/{id}.{ext}"
    u = tpl.Replace("{base}", b)
    u = u.Replace("{kind}", kind)
    u = u.Replace("{user}", Enc(StrOf(m.login.user)))
    u = u.Replace("{pass}", Enc(StrOf(m.login.pass)))
    u = u.Replace("{id}", id)
    u = u.Replace("{ext}", ext)
    return u
end function

function cleanStreamBase(u as string) as string
    u = u.Trim()
    while Right(u, 1) = "/"
        u = Left(u, Len(u) - 1)
    end while
    if u <> "" and Instr(1, u, "://") = 0 then u = "http://" + u
    return u
end function

' Enderecos a tentar, na ordem (formato escolhido primeiro; o que funcionar fica lembrado)
function urlOptions(it as object) as object
    list = []
    if it.kind = "live" then
        fmt = RegRead("format", "auto")
        ' no Roku o HLS (.m3u8) e o formato nativo; o TS fica como segunda opcao
        order = ["m3u8", "ts"]
        if fmt = "ts" then order = ["ts", "m3u8"]
        for each e in order
            list.Push(buildUrl("live", it.id, e))
        end for
        list.Push(buildUrl("live", it.id, "").Replace("/" + it.id + ".", "/" + it.id))
        ' formato antigo Xtream: {base}/{user}/{pass}/{id}
        old = buildUrl("live", it.id, "ts").Replace("/live/", "/")
        list.Push(old)
    else
        kind = "movie"
        if it.kind = "episode" then kind = "series"
        first = it.ext
        if first = "" then first = "mp4"
        list.Push(buildUrl(kind, it.id, first))
        for each e in ["mp4", "mkv", "m3u8", "ts"]
            if e <> first then list.Push(buildUrl(kind, it.id, e))
        end for
    end if
    ' remove repetidos
    seen = {}
    out2 = []
    for each u in list
        if not seen.DoesExist(u) then
            seen[u] = true
            out2.Push(u)
        end if
    end for
    return out2
end function

sub playItem(it as object, index as integer)
    m.playing = it
    m.playIndex = index
    m.urls = urlOptions(it)
    m.variant = 0
    pv = m.prefVariant[it.kind]
    if pv <> invalid and pv < m.urls.Count() then m.variant = pv
    m.tried = 0
    startPlayback()
end sub

sub startPlayback()
    it = m.playing
    url = m.urls[m.variant]
    c = CreateObject("roSGNode", "ContentNode")
    c.url = url
    c.title = it.name
    c.streamFormat = StreamFormatOf(url)
    if it.kind = "live" then c.live = true
    ua = StrOf(m.login.server.user_agent)
    if ua <> "" then c.HttpHeaders = ["User-Agent:" + ua]
    m.video.content = c
    ' modo compativel: limita a 720p (menos dados, aparelhos simples) quando o Roku suporta
    if m.video.HasField("maxVideoDecodeResolution") then
        if RegRead("player", "normal") = "compat" then
            m.video.SetField("maxVideoDecodeResolution", [1280, 720])
        else
            m.video.SetField("maxVideoDecodeResolution", [3840, 2160])
        end if
    end if
    m.video.visible = true
    m.video.SetFocus(true)
    m.video.control = "play"
    showOsd(it.name)
    m.stallTimer.duration = 20
    m.stallTimer.control = "stop"
    m.stallTimer.control = "start"
end sub

function nextVariant() as boolean
    if m.urls = invalid or m.tried >= m.urls.Count() - 1 then return false
    m.tried = m.tried + 1
    m.variant = (m.variant + 1) mod m.urls.Count()
    return true
end function

sub onVideoState()
    st = m.video.state
    if st = "playing" then
        m.stallTimer.control = "stop"
        if m.playing <> invalid then m.prefVariant[m.playing.kind] = m.variant
    else if st = "error" then
        if nextVariant() then
            showOsd("Tentando outro formato...")
            startPlayback()
        else
            showOsd("Nao foi possivel reproduzir. Tente outro canal ou mude o formato nas Configuracoes.")
            m.stallTimer.control = "stop"
        end if
    else if st = "finished" then
        if m.playing <> invalid and m.playing.kind = "live" then
            startPlayback()
        else
            closeVideo()
        end if
    end if
end sub

sub onStall()
    if not m.video.visible then return
    if m.video.state = "playing" then return
    if m.playing <> invalid and m.playing.kind = "live" and nextVariant() then
        showOsd("Conexao lenta... tentando outro formato")
        startPlayback()
    else if m.playing <> invalid and m.playing.kind <> "live" then
        showOsd("Conexao lenta: carregando, aguarde...")
    else
        showOsd("Canal lento ou fora do ar.")
    end if
end sub

sub closeVideo()
    m.stallTimer.control = "stop"
    m.video.control = "stop"
    m.video.visible = false
    m.osd.visible = false
    restoreFocus()
end sub

sub zapChannel(delta as integer)
    if m.liveList = invalid or m.liveList.Count() = 0 or m.playing = invalid or m.playing.kind <> "live" then return
    n = m.liveList.Count()
    i = (m.playIndex + delta + n) mod n
    it = m.liveList[i]
    if it.kind <> "live" then return
    m.items.jumpToItem = i
    playItem(it, i)
end sub

sub showOsd(text as string)
    m.osd.text = text
    m.osd.visible = m.video.visible
    m.osdTimer.control = "stop"
    m.osdTimer.control = "start"
    if not m.video.visible then m.status.text = text
end sub

sub hideOsd()
    m.osd.visible = false
end sub

' ---------------------------------------------------------------- foco e teclas
sub restoreFocus()
    if m.loginBox.visible then
        m.loginList.SetFocus(true)
    else if m.focusArea = "items" then
        if m.grid.visible then
            m.grid.SetFocus(true)
        else
            m.items.SetFocus(true)
        end if
    else if m.focusArea = "cats" then
        m.cats.SetFocus(true)
    else
        m.menu.SetFocus(true)
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.video.visible then
        if key = "back" then
            closeVideo()
            return true
        else if key = "up" or key = "channelup" then
            zapChannel(-1)
            return true
        else if key = "down" or key = "channeldown" then
            zapChannel(1)
            return true
        end if
        return false
    end if
    if not m.home.visible then return false
    if key = "options" then
        toggleFavorite()
        return true
    end if
    if key = "right" then
        if m.focusArea = "menu" and m.mode <> "fav" and m.mode <> "settings" then
            m.focusArea = "cats"
        else if m.focusArea = "menu" or m.focusArea = "cats" then
            m.focusArea = "items"
        end if
        restoreFocus()
        return true
    else if key = "left" then
        if m.focusArea = "items" and (m.mode = "fav" or m.mode = "settings") then
            m.focusArea = "menu"
        else if m.focusArea = "items" then
            m.focusArea = "cats"
        else if m.focusArea = "cats" then
            m.focusArea = "menu"
        end if
        restoreFocus()
        return true
    else if key = "back" then
        if m.series <> invalid and m.focusArea = "items" then
            m.series = invalid
            if m.mode = "fav" then
                showFavorites()
            else
                onCategory()
            end if
            restoreFocus()
            return true
        end if
        if m.focusArea = "items" or m.focusArea = "cats" then
            if m.focusArea = "items" and m.mode <> "fav" and m.mode <> "settings" then
                m.focusArea = "cats"
            else
                m.focusArea = "menu"
            end if
            restoreFocus()
            return true
        end if
        return false
    end if
    return false
end function
