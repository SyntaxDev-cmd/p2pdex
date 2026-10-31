' Funcoes comuns (tela e tarefas)

function AppConfig() as object
    raw = ReadAsciiFile("pkg:/config.json")
    cfg = invalid
    if raw <> "" then cfg = ParseJson(raw)
    if cfg = invalid then cfg = {}
    if cfg.panel_url = invalid then cfg.panel_url = ""
    if cfg.panel_key = invalid then cfg.panel_key = ""
    if cfg.reseller_code = invalid then cfg.reseller_code = ""
    if cfg.app_mode = invalid then cfg.app_mode = ""
    if cfg.app_name = invalid then cfg.app_name = "NovaTV"
    if cfg.accent = invalid then cfg.accent = "#00AAFF"
    return cfg
end function

function RegRead(key as string, def = "" as string) as string
    sec = CreateObject("roRegistrySection", "novatv")
    if sec.Exists(key) then return sec.Read(key)
    return def
end function

sub RegWrite(key as string, value as string)
    sec = CreateObject("roRegistrySection", "novatv")
    sec.Write(key, value)
    sec.Flush()
end sub

sub RegDelete(key as string)
    sec = CreateObject("roRegistrySection", "novatv")
    sec.Delete(key)
    sec.Flush()
end sub

function Enc(s as string) as string
    t = CreateObject("roUrlTransfer")
    return t.Escape(s)
end function

function StrOf(v as dynamic) as string
    if v = invalid then return ""
    t = type(v)
    if t = "roString" or t = "String" then return v
    if t = "roInteger" or t = "Integer" or t = "roInt" then return v.ToStr()
    if t = "roFloat" or t = "Float" or t = "roDouble" or t = "Double" then return Str(v).Trim()
    if t = "roBoolean" or t = "Boolean" then
        if v then return "1"
        return "0"
    end if
    return ""
end function

' "http://x/live/u/p/123.ts" -> extensao "ts"
function ExtOf(url as string) as string
    path = url
    q = Instr(1, path, "?")
    if q > 0 then path = Left(path, q - 1)
    slash = 0
    for i = Len(path) to 1 step -1
        if Mid(path, i, 1) = "/" then
            slash = i
            exit for
        end if
    end for
    file = Mid(path, slash + 1)
    dot = 0
    for i = Len(file) to 1 step -1
        if Mid(file, i, 1) = "." then
            dot = i
            exit for
        end if
    end for
    if dot = 0 then return ""
    return LCase(Mid(file, dot + 1))
end function

function StreamFormatOf(url as string) as string
    e = ExtOf(url)
    if e = "m3u8" or e = "m3u" then return "hls"
    if e = "mpd" then return "dash"
    if e = "ts" then return "ts"
    if e = "mkv" then return "mkv"
    if e = "mp4" or e = "m4v" or e = "mov" then return "mp4"
    return "hls"
end function

function Matches(name as string, words as object) as boolean
    if words = invalid then return false
    n = LCase(name)
    for each w in words
        ww = LCase(StrOf(w)).Trim()
        if ww <> "" and Instr(1, n, ww) > 0 then return true
    end for
    return false
end function
