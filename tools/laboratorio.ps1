# Laboratorio DEXONDEV - gera NovaTV / NovaSports personalizados no seu PC (Windows 10/11).
# Abra pelo LABORATORIO.bat (dois cliques). Na 1a vez baixa sozinho o Java 17 e o Android SDK
# para a pasta "ferramentas" (nada e instalado no Windows).
param([string]$Task = "", [string]$Arg = "", [string]$LogFile = "")

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Root   = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Tools  = Join-Path $Root "ferramentas"
$Jdk    = Join-Path $Tools "jdk"
$Sdk    = Join-Path $Tools "android-sdk"
$Orig   = Join-Path $Tools "original"
$Out    = Join-Path $Root "SAIDA"
$Keys   = Join-Path $Root "chave"
$Prof   = Join-Path $Root "perfis"
$LogDir = Join-Path $Tools "logs"
# modelos de app que o laboratorio gera (dir = projeto; mode = variacao; kind = android ou roku)
$Models = [ordered]@{
    "NovaTV"          = @{ dir = "android";        mode = "";       kind = "android"; desc = "Android: canais, filmes, series, kids, anime" }
    "NovaSports"      = @{ dir = "sports\android"; mode = "";       kind = "android"; desc = "Android: futebol, jogos ao vivo, noticias, tabelas" }
    "NovaKids"        = @{ dir = "android";        mode = "kids";   kind = "android"; desc = "Android: so conteudo infantil" }
    "Roku - NovaTV"   = @{ dir = "roku";           mode = "";       kind = "roku";    desc = "Roku: canais, filmes e series (leve)" }
    "Roku - Kids"     = @{ dir = "roku";           mode = "kids";   kind = "roku";    desc = "Roku: so canais infantis" }
    "Roku - Esportes" = @{ dir = "roku";           mode = "sports"; kind = "roku";    desc = "Roku: so canais de esporte" }
}
# arquivos de marca do Roku (restaurados do original antes de cada geracao)
$RokuBrandFiles = @("images", "config.json", "manifest")
# arquivos de marca do Android (restaurados do original antes de cada geracao)
$BrandFiles = @(
    "app\src\main\res\drawable-nodpi", "app\src\main\res\drawable-xhdpi\banner.png",
    "app\src\main\res\mipmap-mdpi\ic_launcher.png", "app\src\main\res\mipmap-hdpi\ic_launcher.png",
    "app\src\main\res\mipmap-xhdpi\ic_launcher.png", "app\src\main\res\mipmap-xxhdpi\ic_launcher.png",
    "app\src\main\res\mipmap-xxxhdpi\ic_launcher.png", "app\src\main\assets\config.json"
)

foreach ($d in @($Tools, $Out, $Keys, $Prof, $LogDir)) { if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d | Out-Null } }

# mensagem para a janela (arquivo de log em UTF-8) - Write-Host nao interfere no retorno das funcoes
function Say([string]$m) {
    $line = "[" + (Get-Date -Format "HH:mm:ss") + "] " + $m
    if ($LogFile) { [IO.File]::AppendAllText($LogFile, $line + "`r`n", (New-Object Text.UTF8Encoding($false))) }
    Write-Host $line
}

# Roda um programa externo gravando a saida em arquivo (sem as armadilhas de aspas/stderr do
# PowerShell 5). Mostra na janela as linhas que casam com $filter e, se falhar, o final da saida.
function Tail-Lines([string]$file, [long]$pos, [string]$filter) {
    if (-not (Test-Path $file)) { return $pos }
    try {
        $fs = [IO.File]::Open($file, "Open", "Read", "ReadWrite")
        [void]$fs.Seek($pos, "Begin")
        $sr = New-Object IO.StreamReader($fs)
        $txt = $sr.ReadToEnd(); $pos = $fs.Position; $sr.Close()
        foreach ($l in ($txt -split "`r?`n")) { if ($l -and $filter -and $l -match $filter) { Say ("  " + $l.Trim()) } }
    } catch { }
    return $pos
}

function Run-Native([string]$exe, [string]$argLine, [string]$workDir, [string]$stdin, [string]$filter) {
    if (-not $workDir) { $workDir = $Root }
    $id = [guid]::NewGuid().ToString("N").Substring(0, 8)
    $outF = Join-Path $LogDir "run-$id.out"; $errF = Join-Path $LogDir "run-$id.err"
    $params = @{ FilePath = $exe; ArgumentList = $argLine; WorkingDirectory = $workDir
                 RedirectStandardOutput = $outF; RedirectStandardError = $errF; NoNewWindow = $true; PassThru = $true }
    if ($stdin) { $params.RedirectStandardInput = $stdin }
    $pr = Start-Process @params
    $null = $pr.Handle   # sem isso o ExitCode pode vir vazio
    $pos = 0
    while (-not $pr.HasExited) { Start-Sleep -Milliseconds 800; $pos = Tail-Lines $outF $pos $filter }
    $pr.WaitForExit()
    $pos = Tail-Lines $outF $pos $filter
    $code = $pr.ExitCode
    if ($code -ne 0) {
        Say "  --- ultimas mensagens ---"
        foreach ($f in @($outF, $errF)) { if (Test-Path $f) { Get-Content $f -Tail 30 | ForEach-Object { if ($_.Trim()) { Say ("  " + $_.Trim()) } } } }
    }
    Remove-Item $outF, $errF -Force -ErrorAction SilentlyContinue
    return $code
}

function Java-Exe { Join-Path $Jdk "bin\java.exe" }
function Tools-Ready {
    (Test-Path (Java-Exe)) -and (Test-Path (Join-Path $Sdk "platforms\android-36")) -and (Test-Path (Join-Path $Sdk "licenses"))
}

# ------------------------------------------------------------------ instalacao das ferramentas
function Download([string]$url, [string]$file) {
    for ($i = 1; $i -le 3; $i++) {
        try { Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing -UserAgent "Mozilla/5.0"; return }
        catch { Say "  tentativa $i falhou: $($_.Exception.Message)"; Start-Sleep -Seconds (3 * $i) }
    }
    throw "Nao consegui baixar $url (verifique a internet/antivirus)."
}

function Install-Tools {
    if (-not (Test-Path (Java-Exe))) {
        Say "Baixando Java 17 (Temurin, ~190 MB)..."
        $zip = Join-Path $Tools "jdk.zip"
        Download "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" $zip
        Say "Descompactando o Java..."
        $tmp = Join-Path $Tools "jdk-tmp"
        if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
        Expand-Archive -Path $zip -DestinationPath $tmp -Force
        $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
        if (Test-Path $Jdk) { Remove-Item $Jdk -Recurse -Force }
        Move-Item $inner.FullName $Jdk
        Remove-Item $tmp -Recurse -Force; Remove-Item $zip -Force
        Say "Java pronto."
    }
    $env:JAVA_HOME = $Jdk
    $env:Path = (Join-Path $Jdk "bin") + ";" + $env:Path
    $sdkm = Join-Path $Sdk "cmdline-tools\latest\bin\sdkmanager.bat"
    if (-not (Test-Path $sdkm)) {
        Say "Baixando Android SDK (ferramentas, ~150 MB)..."
        $zip = Join-Path $Tools "cmdline.zip"
        Download "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" $zip
        $tmp = Join-Path $Tools "cmd-tmp"
        if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
        Expand-Archive -Path $zip -DestinationPath $tmp -Force
        $dest = Join-Path $Sdk "cmdline-tools"
        New-Item -ItemType Directory -Path $dest -Force | Out-Null
        if (Test-Path (Join-Path $dest "latest")) { Remove-Item (Join-Path $dest "latest") -Recurse -Force }
        Move-Item (Join-Path $tmp "cmdline-tools") (Join-Path $dest "latest")
        Remove-Item $tmp -Recurse -Force; Remove-Item $zip -Force
    }
    Say "Aceitando licencas do Android SDK..."
    $yesF = Join-Path $LogDir "yes.txt"
    [IO.File]::WriteAllText($yesF, ("y`r`n" * 60))
    $c = Run-Native "cmd.exe" "/c `"`"$sdkm`" --sdk_root=`"$Sdk`" --licenses`"" $Tools $yesF "accepted|Error"
    Say "Instalando Android 16 (API 36) e build-tools (~250 MB, pode demorar)..."
    $c = Run-Native "cmd.exe" "/c `"`"$sdkm`" --sdk_root=`"$Sdk`" platform-tools `"platforms;android-36`" `"build-tools;35.0.0`"`"" $Tools "" "Installing|Unzipping|done|Error|Warning"
    if (-not (Test-Path (Join-Path $Sdk "platforms\android-36"))) { throw "O Android SDK nao foi instalado (codigo $c). Veja as mensagens acima e rode de novo." }
    Say "Ferramentas prontas! Agora e so gerar os apps."
}

# ------------------------------------------------------------------ marca (logo, icones, fundo)
Add-Type -AssemblyName System.Drawing

function Load-Image([string]$path) {
    $bytes = [IO.File]::ReadAllBytes($path)
    $ms = New-Object IO.MemoryStream(, $bytes)
    return [Drawing.Image]::FromStream($ms)
}

function Fit-Into([Drawing.Image]$img, [int]$w, [int]$h, [double]$pad, $bg) {
    $bmp = New-Object Drawing.Bitmap($w, $h, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    if ($bg -ne $null) { $g.Clear($bg) } else { $g.Clear([Drawing.Color]::Transparent) }
    $bw = $w * (1 - 2 * $pad); $bh = $h * (1 - 2 * $pad)
    $s = [Math]::Min($bw / $img.Width, $bh / $img.Height)
    $dw = [int]($img.Width * $s); $dh = [int]($img.Height * $s)
    $g.DrawImage($img, [int](($w - $dw) / 2), [int](($h - $dh) / 2), $dw, $dh)
    $g.Dispose()
    return $bmp
}

function Save-Png([Drawing.Bitmap]$bmp, [string]$path) { $bmp.Save($path, [Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose() }

function Clear-Res([string]$folder, [string]$name) {
    Get-ChildItem $folder -File | Where-Object { [IO.Path]::GetFileNameWithoutExtension($_.Name) -eq $name } | Remove-Item -Force
}

function Backup-Original([string]$proj, [string]$app, $files = $BrandFiles) {
    $dst = Join-Path $Orig $app
    if (Test-Path $dst) { return }
    foreach ($f in $files) {
        $src = Join-Path $proj $f
        if (-not (Test-Path $src)) { continue }
        $to = Join-Path $dst $f
        New-Item -ItemType Directory -Path (Split-Path -Parent $to) -Force | Out-Null
        Copy-Item $src $to -Recurse -Force
    }
}

function Restore-Original([string]$proj, [string]$app, $files = $BrandFiles) {
    $src = Join-Path $Orig $app
    if (-not (Test-Path $src)) { return }
    $nodpi = Join-Path $proj "app\src\main\res\drawable-nodpi"
    if (Test-Path $nodpi) { Get-ChildItem $nodpi -File | Remove-Item -Force }
    foreach ($f in $files) {
        $from = Join-Path $src $f
        if (-not (Test-Path $from)) { continue }
        $to = Join-Path $proj $f
        if ((Get-Item $from).PSIsContainer) { Copy-Item (Join-Path $from "*") $to -Recurse -Force } else { Copy-Item $from $to -Force }
    }
}

function Apply-Brand($p, [string]$proj) {
    $res = Join-Path $proj "app\src\main\res"
    $bg = [Drawing.ColorTranslator]::FromHtml($p.color)
    if ($p.logo) {
        $logo = Load-Image $p.logo
        $s = [Math]::Min(1.0, 1024.0 / [Math]::Max($logo.Width, $logo.Height))
        Clear-Res (Join-Path $res "drawable-nodpi") "app_logo"
        Save-Png (Fit-Into $logo ([int]($logo.Width * $s)) ([int]($logo.Height * $s)) 0 $null) (Join-Path $res "drawable-nodpi\app_logo.png")
        Save-Png (Fit-Into $logo 320 180 0.12 $bg) (Join-Path $res "drawable-xhdpi\banner.png")
        Say "  logo e banner da TV aplicados"
    }
    $iconSrc = $p.icon; if (-not $iconSrc) { $iconSrc = $p.logo }
    if ($iconSrc) {
        $icon = Load-Image $iconSrc
        $sizes = [ordered]@{ "mdpi" = 48; "hdpi" = 72; "xhdpi" = 96; "xxhdpi" = 144; "xxxhdpi" = 192 }
        foreach ($k in $sizes.Keys) {
            $n = $sizes[$k]
            if ($p.icon) { $b = Fit-Into $icon $n $n 0 $null } else { $b = Fit-Into $icon $n $n 0.1 $bg }
            Save-Png $b (Join-Path $res "mipmap-$k\ic_launcher.png")
        }
        Say "  icones aplicados"
    }
    if ($p.background) {
        $im = Load-Image $p.background
        $s = [Math]::Min(1.0, 1920.0 / [Math]::Max($im.Width, $im.Height))
        $b = Fit-Into $im ([int]($im.Width * $s)) ([int]($im.Height * $s)) 0 ([Drawing.Color]::Black)
        Clear-Res (Join-Path $res "drawable-nodpi") "app_background"
        $enc = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq "image/jpeg" }
        $ep = New-Object Drawing.Imaging.EncoderParameters(1)
        $ep.Param[0] = New-Object Drawing.Imaging.EncoderParameter([Drawing.Imaging.Encoder]::Quality, [long]85)
        $b.Save((Join-Path $res "drawable-nodpi\app_background.jpg"), $enc, $ep); $b.Dispose()
        Say "  fundo aplicado"
    }
    $cfgPath = Join-Path $proj "app\src\main\assets\config.json"
    $cfg = Get-Content $cfgPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($p.panel) { $cfg | Add-Member -NotePropertyName panel_url -NotePropertyValue $p.panel.Trim().TrimEnd("/") -Force }
    if ($p.key) { $cfg | Add-Member -NotePropertyName panel_key -NotePropertyValue $p.key.Trim() -Force }
    if ($p.reseller) { $cfg | Add-Member -NotePropertyName reseller_code -NotePropertyValue $p.reseller.Trim().ToUpper() -Force }
    $cfg | Add-Member -NotePropertyName app_mode -NotePropertyValue ([string]$p.mode) -Force
    [IO.File]::WriteAllText($cfgPath, ($cfg | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))
    Say "  painel/config gravados"
}

# ------------------------------------------------------------------ Roku (marca + pacote .zip)
function Cover-Into([Drawing.Image]$img, [int]$w, [int]$h) {
    $bmp = New-Object Drawing.Bitmap($w, $h, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $s = [Math]::Max($w / $img.Width, $h / $img.Height)
    $dw = [int]($img.Width * $s) + 1; $dh = [int]($img.Height * $s) + 1
    $g.DrawImage($img, [int](($w - $dw) / 2), [int](($h - $dh) / 2), $dw, $dh)
    $g.Dispose()
    return $bmp
}

function Save-Jpg([Drawing.Bitmap]$bmp, [string]$path) {
    $enc = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq "image/jpeg" }
    $ep = New-Object Drawing.Imaging.EncoderParameters(1)
    $ep.Param[0] = New-Object Drawing.Imaging.EncoderParameter([Drawing.Imaging.Encoder]::Quality, [long]85)
    $bmp.Save($path, $enc, $ep); $bmp.Dispose()
}

function Apply-Brand-Roku($p, [string]$proj) {
    $img = Join-Path $proj "images"
    $bg = [Drawing.ColorTranslator]::FromHtml($p.color)
    $iconSrc = $p.icon; if (-not $iconSrc) { $iconSrc = $p.logo }
    if ($iconSrc) {
        $icon = Load-Image $iconSrc
        $pad = 0.1; $col = $bg
        if ($p.icon) { $pad = 0; $col = $bg }
        Save-Png (Fit-Into $icon 336 210 $pad $col) (Join-Path $img "icon_hd.png")
        Save-Png (Fit-Into $icon 246 140 $pad $col) (Join-Path $img "icon_sd.png")
        Say "  icones do Roku aplicados"
    }
    if ($p.logo) {
        $logo = Load-Image $p.logo
        $s = [Math]::Min(1.0, 600.0 / [Math]::Max($logo.Width, $logo.Height))
        Save-Png (Fit-Into $logo ([int]($logo.Width * $s)) ([int]($logo.Height * $s)) 0 $null) (Join-Path $img "logo.png")
    }
    if ($p.background) {
        $back = Load-Image $p.background
        Save-Jpg (Cover-Into $back 1920 1080) (Join-Path $img "bg.jpg")
    }
    # abertura (splash): fundo + logo
    $splashBg = $null
    if ($p.background) { $splashBg = Load-Image $p.background } else { $splashBg = Load-Image (Join-Path $img "bg.jpg") }
    $logoImg = Load-Image (Join-Path $img "logo.png")
    foreach ($sz in @(@(1920, 1080, "splash_fhd.jpg"), @(1280, 720, "splash_hd.jpg"))) {
        $b = Cover-Into $splashBg $sz[0] $sz[1]
        $g = [Drawing.Graphics]::FromImage($b)
        $g.FillRectangle((New-Object Drawing.SolidBrush([Drawing.Color]::FromArgb(170, 7, 10, 20))), 0, 0, $sz[0], $sz[1])
        $l = Fit-Into $logoImg $sz[0] $sz[1] 0.3 $null
        $g.DrawImage($l, 0, 0, $sz[0], $sz[1]); $l.Dispose(); $g.Dispose()
        Save-Jpg $b (Join-Path $img $sz[2])
    }
    Say "  imagens do Roku aplicadas"
    $cfgPath = Join-Path $proj "config.json"
    $cfg = Get-Content $cfgPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $cfg | Add-Member -NotePropertyName app_name -NotePropertyValue $p.label -Force
    if ($p.panel) { $cfg | Add-Member -NotePropertyName panel_url -NotePropertyValue $p.panel.Trim().TrimEnd("/") -Force }
    if ($p.key) { $cfg | Add-Member -NotePropertyName panel_key -NotePropertyValue $p.key.Trim() -Force }
    if ($p.reseller) { $cfg | Add-Member -NotePropertyName reseller_code -NotePropertyValue $p.reseller.Trim().ToUpper() -Force }
    $cfg | Add-Member -NotePropertyName app_mode -NotePropertyValue ([string]$p.mode) -Force
    [IO.File]::WriteAllText($cfgPath, ($cfg | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))
    # manifest: nome e versao
    $man = Join-Path $proj "manifest"
    $lines = Get-Content $man -Encoding UTF8 | ForEach-Object {
        if ($_ -match "^title=") { "title=" + $p.label }
        elseif ($_ -match "^build_version=") { "build_version=" + $p.version }
        elseif ($_ -match "^splash_color=") { "splash_color=" + $p.color }
        else { $_ }
    }
    [IO.File]::WriteAllText($man, (($lines -join "`n") + "`n"), (New-Object Text.UTF8Encoding($false)))
    Say "  nome, painel e modo gravados"
}

# ZIP com barras "/" (o Compress-Archive do Windows antigo grava "\" e o Roku recusa)
function Zip-Folder([string]$folder, [string]$zipPath) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    $zip = [IO.Compression.ZipFile]::Open($zipPath, "Create")
    try {
        $base = (Resolve-Path $folder).Path.TrimEnd("\") + "\"
        Get-ChildItem $folder -Recurse -File | Where-Object { $_.Name -ne "bsconfig.json" } | ForEach-Object {
            $rel = $_.FullName.Substring($base.Length).Replace("\", "/")
            [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $rel, "Optimal")
        }
    } finally { $zip.Dispose() }
}

function Build-Roku($p, [string]$proj, [string]$backupKey) {
    Say "Preparando Roku -> '$($p.label)' (modo: $(if ($p.mode) { $p.mode } else { 'completo' }))"
    Backup-Original $proj $backupKey $RokuBrandFiles
    Restore-Original $proj $backupKey $RokuBrandFiles
    try {
        Apply-Brand-Roku $p $proj
        $safe = ($p.label -replace "[^A-Za-z0-9]", ""); if (-not $safe) { $safe = "App" }
        $dest = Join-Path $Out ("{0}-roku-v{1}.zip" -f $safe, $p.version)
        Zip-Folder $proj $dest
    } finally { Restore-Original $proj $backupKey $RokuBrandFiles }
    Say "PRONTO! Pacote Roku: $dest"
    Say "Para instalar: ative o modo desenvolvedor no Roku e envie este .zip pelo navegador (veja o tutorial)."
}

# ------------------------------------------------------------------ chave de assinatura fixa
function Ensure-Key {
    $ks = Join-Path $Keys "laboratorio.jks"
    $pw = Join-Path $Keys "senha.txt"
    if (-not (Test-Path $ks)) {
        $chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".ToCharArray()
        $pass = -join (1..20 | ForEach-Object { $chars | Get-Random })
        Say "Criando a chave de assinatura do laboratorio (guarde a pasta 'chave'!)..."
        $kt = Join-Path $Jdk "bin\keytool.exe"
        [void](Run-Native $kt "-genkeypair -keystore `"$ks`" -storepass $pass -keypass $pass -alias dexondev -keyalg RSA -keysize 2048 -validity 10000 -dname `"CN=DEXONDEV, O=DEXONDEV, C=BR`"" $Keys "" "")
        if (-not (Test-Path $ks)) { throw "Nao consegui criar a chave de assinatura." }
        [IO.File]::WriteAllText($pw, $pass)
    }
    return @{ file = $ks; pass = (Get-Content $pw -Raw).Trim() }
}

# ------------------------------------------------------------------ geracao do APK
function Build-App([string]$profilePath) {
    $p = Get-Content $profilePath -Raw -Encoding UTF8 | ConvertFrom-Json
    $model = $Models[[string]$p.app]
    if (-not $model) { throw "Modelo '$($p.app)' desconhecido." }
    $proj = Join-Path $Root $model.dir
    if (-not (Test-Path $proj)) { throw "Projeto $($p.app) nao encontrado em $proj" }
    $p | Add-Member -NotePropertyName mode -NotePropertyValue $model.mode -Force
    $backupKey = ($model.dir -replace "[\\/]", "_")
    if ($model.kind -eq "roku") { Build-Roku $p $proj $backupKey; return }
    if (-not (Tools-Ready)) { Install-Tools }
    $env:JAVA_HOME = $Jdk
    $env:ANDROID_HOME = $Sdk
    $env:ANDROID_SDK_ROOT = $Sdk
    $env:Path = (Join-Path $Jdk "bin") + ";" + $env:Path
    [IO.File]::WriteAllText((Join-Path $proj "local.properties"), "sdk.dir=" + ($Sdk -replace "\\", "\\\\") + "`n")

    Say "Preparando $($p.app) -> '$($p.label)' ($($p.id))"
    Backup-Original $proj $backupKey
    Restore-Original $proj $backupKey
    Apply-Brand $p $proj
    $key = Ensure-Key

    $env:APP_LABEL = $p.label
    $env:APP_ID = $p.id
    $env:PANEL_URL = $p.panel
    $env:PANEL_KEY = $p.key
    $env:VERSION_CODE = [string]$p.version
    $env:VERSION_NAME = "1.0." + $p.version
    $env:KEYSTORE_FILE = $key.file
    $env:KEYSTORE_PASSWORD = $key.pass
    $env:KEY_ALIAS = "dexondev"
    $env:KEY_PASSWORD = $key.pass

    $tasks = "assembleFullRelease"
    if ($p.play) { $tasks += " bundlePlayRelease"; Say "Tambem vou gerar a versao Google Play (.aab)." }
    Say "Compilando (a 1a vez baixa o Gradle e demora uns 5-10 min; depois ~1-2 min)..."
    try {
        $c = Run-Native "cmd.exe" "/c `"`"$proj\gradlew.bat`" $tasks --no-daemon --console=plain`"" $proj "" "^e: |error:|FAILED|BUILD SUCCESSFUL|What went wrong|Downloading https://services.gradle|> Task :app:(compile|assemble|package|bundle)"
        $ok = ($c -eq 0)
    } finally { Restore-Original $proj $backupKey }
    if (-not $ok) { throw "A compilacao falhou. Veja as linhas acima." }

    $apk = Get-ChildItem (Join-Path $proj "app\build\outputs\apk\full\release") -Filter *.apk | Select-Object -First 1
    $safe = ($p.label -replace "[^A-Za-z0-9]", "")
    if (-not $safe) { $safe = "App" }
    $dest = Join-Path $Out ("{0}-v{1}.apk" -f $safe, $p.version)
    Copy-Item $apk.FullName $dest -Force
    Say "PRONTO! APK: $dest"
    if ($p.play) {
        $aab = Get-ChildItem (Join-Path $proj "app\build\outputs\bundle\playRelease") -Filter *.aab -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($aab) {
            $destA = Join-Path $Out ("{0}-play-v{1}.aab" -f $safe, $p.version)
            Copy-Item $aab.FullName $destA -Force
            Say "PRONTO! Google Play: $destA"
        }
    }
}

# ------------------------------------------------------------------ instalar/atualizar numa pasta do PC
# Copia o laboratorio (deste pacote) para $target. Tudo que for substituido vai antes para
# <target>\ferramentas\backup\<data>; clientes, chave, ferramentas e APKs nao sao tocados.
$PackageItems = @("android", "sports", "roku", "tools", "LABORATORIO.bat", "ATUALIZAR.bat", "TUTORIAL-LABORATORIO.txt", "README.md")
function Install-Into([string]$target) {
    $target = $target.Trim().Trim('"').TrimEnd("\")
    if (-not $target) { throw "Pasta de destino vazia." }
    if (-not (Test-Path $target)) { New-Item -ItemType Directory -Path $target -Force | Out-Null }
    if ((Resolve-Path $target).Path -eq (Resolve-Path $Root).Path) { Say "Ja esta na pasta certa."; return }
    # pasta com arquivos de OUTRO laboratorio (mesmos nomes, mas nao sao nossos): nao mexe neles
    $foreign = @()
    if ((Test-Path (Join-Path $target "tools")) -and -not (Test-Path (Join-Path $target "tools\laboratorio.ps1"))) { $foreign += "tools" }
    if ((Test-Path (Join-Path $target "android")) -and -not (Test-Path (Join-Path $target "android\app\src\main\java\com\novatv"))) { $foreign += "android" }
    if ((Test-Path (Join-Path $target "sports")) -and -not (Test-Path (Join-Path $target "sports\android\gradlew.bat"))) { $foreign += "sports" }
    if ((Test-Path (Join-Path $target "roku")) -and -not (Test-Path (Join-Path $target "roku\components\MainScene.brs"))) { $foreign += "roku" }
    if ($foreign.Count -gt 0) {
        Say ("A pasta ja tem arquivos de outro laboratorio (" + ($foreign -join ", ") + "). Eles NAO serao alterados.")
        $target = Join-Path $target "NOVA-LAB"
        if (-not (Test-Path $target)) { New-Item -ItemType Directory -Path $target -Force | Out-Null }
        Say "O laboratorio novo vai para: $target"
    }
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $bk = Join-Path $target "ferramentas\backup\$stamp"
    foreach ($it in $PackageItems) {
        $src = Join-Path $Root $it
        if (-not (Test-Path $src)) { continue }
        $dst = Join-Path $target $it
        if (Test-Path $dst) {
            New-Item -ItemType Directory -Path $bk -Force | Out-Null
            Move-Item $dst (Join-Path $bk $it) -Force
            Say "  backup: $it -> ferramentas\backup\$stamp"
        }
        Copy-Item $src $dst -Recurse -Force
        Say "  atualizado: $it"
    }
    # a marca original pode ter mudado no pacote novo
    $o = Join-Path $target "ferramentas\original"
    if (Test-Path $o) { Remove-Item $o -Recurse -Force }
    Say "Laboratorio instalado/atualizado em $target"
}

# ------------------------------------------------------------------ gerar todos os modelos de uma vez
# Mesmo cliente (nome, logo, painel...) em todos os modelos; cada um ganha pacote e versao proprios.
$ModelSuffix = [ordered]@{
    "NovaTV" = @("", ""); "NovaSports" = @(".sports", " Sports"); "NovaKids" = @(".kids", " Kids")
    "Roku - NovaTV" = @(".roku", ""); "Roku - Kids" = @(".rokukids", " Kids"); "Roku - Esportes" = @(".rokusports", " Sports")
}
function Build-All([string]$profilePath) {
    $base = Get-Content $profilePath -Raw -Encoding UTF8 | ConvertFrom-Json
    $ok = @(); $fail = @()
    foreach ($name in $Models.Keys) {
        $suf = $ModelSuffix[$name]
        $id = $base.id + $suf[0]
        $label = ($base.label + $suf[1])
        if ($label.Length -gt 30) { $label = $label.Substring(0, 30).Trim() }
        $pf = Join-Path $Prof ($id + ".json")
        $ver = 1
        if ($id -eq $base.id) { $ver = [int]$base.version }   # perfil do proprio cliente: a janela ja subiu a versao
        elseif (Test-Path $pf) { try { $ver = [int]((Get-Content $pf -Raw -Encoding UTF8 | ConvertFrom-Json).version) + 1 } catch { $ver = 1 } }
        $p = [ordered]@{
            app = $name; label = $label; id = $id; panel = $base.panel; key = $base.key; reseller = $base.reseller
            logo = $base.logo; icon = $base.icon; background = $base.background; color = $base.color; version = $ver; play = [bool]$base.play
        }
        [IO.File]::WriteAllText($pf, ($p | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
        Say "=============== $name ($label) ==============="
        try { Build-App $pf; $ok += $name }
        catch { Say ("ERRO em ${name}: " + $_.Exception.Message); $fail += $name }
    }
    Say ("Resumo: " + $ok.Count + " gerado(s): " + ($ok -join ", "))
    if ($fail.Count -gt 0) { throw ("Falharam: " + ($fail -join ", ") + ". Os outros estao na pasta SAIDA.") }
}

# ------------------------------------------------------------------ atualizar o proprio laboratorio
$UpdateUrl = "https://github.com/SyntaxDev-cmd/p2pdex/releases/download/laboratorio/LABORATORIO-APP-DEXONDEV.zip"
function Update-Self {
    $dir = Join-Path $Tools "atualizacao"
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
    New-Item -ItemType Directory -Path $dir | Out-Null
    $zip = Join-Path $dir "lab.zip"
    Say "Baixando a versao mais nova do laboratorio..."
    Download $UpdateUrl $zip
    Expand-Archive -Path $zip -DestinationPath (Join-Path $dir "pkg") -Force
    $script = Join-Path $dir "pkg\tools\laboratorio.ps1"
    if (-not (Test-Path $script)) { throw "Pacote baixado esta incompleto." }
    $argLine = "-NoProfile -ExecutionPolicy Bypass -File `"$script`" -Task install -Arg `"$Root`""
    if ($LogFile) { $argLine += " -LogFile `"$LogFile`"" }
    $c = Run-Native "powershell.exe" $argLine $dir "" ""
    if ($c -ne 0) { throw "Nao consegui instalar a atualizacao (codigo $c)." }
    Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue
    Say "Laboratorio atualizado! Feche esta janela e abra o LABORATORIO.bat de novo."
}

# ------------------------------------------------------------------ modo tarefa (rodado em segundo plano pela janela)
if ($Task -and $Task -ne "guitest") {
    if ($LogFile) { [IO.File]::WriteAllText($LogFile, "", (New-Object Text.UTF8Encoding($false))) }
    try {
        if ($Task -eq "setup") { Install-Tools }
        elseif ($Task -eq "install") { Install-Into $Arg }
        elseif ($Task -eq "build") { Build-App $Arg }
        elseif ($Task -eq "buildall") { Build-All $Arg }
        elseif ($Task -eq "selfupdate") { Update-Self }
        Say "@@OK@@"
    } catch {
        Say ("ERRO: " + $_.Exception.Message)
        Say "@@FALHOU@@"
    }
    exit
}

# ------------------------------------------------------------------ janela
Add-Type -AssemblyName System.Windows.Forms
[Windows.Forms.Application]::EnableVisualStyles()

$C_BG = [Drawing.Color]::FromArgb(14, 18, 32)
$C_CARD = [Drawing.Color]::FromArgb(24, 30, 52)
$C_TXT = [Drawing.Color]::FromArgb(235, 240, 255)
$C_SUB = [Drawing.Color]::FromArgb(150, 160, 190)
$C_ACC = [Drawing.Color]::FromArgb(0, 170, 255)
$fontT = New-Object Drawing.Font("Segoe UI", 18, [Drawing.FontStyle]::Bold)
$font = New-Object Drawing.Font("Segoe UI", 10)
$fontB = New-Object Drawing.Font("Segoe UI", 10, [Drawing.FontStyle]::Bold)

$form = New-Object Windows.Forms.Form
$form.Text = "Laboratorio DEXONDEV"
# cabe em telas pequenas (notebook 1366x768): rola quando precisar
$wa = [Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$form.Size = New-Object Drawing.Size(980, [Math]::Min(880, $wa.Height))
$form.MinimumSize = New-Object Drawing.Size(700, 500)
$form.AutoScroll = $true
$form.StartPosition = "CenterScreen"
$form.BackColor = $C_BG
$form.ForeColor = $C_TXT
$form.Font = $font

$title = New-Object Windows.Forms.Label
$title.Text = "LABORATORIO DEXONDEV"; $title.Font = $fontT; $title.ForeColor = $C_ACC
$title.Location = New-Object Drawing.Point(24, 14); $title.AutoSize = $true
$form.Controls.Add($title)
$sub = New-Object Windows.Forms.Label
$sub.Text = "Gere o NovaTV ou o NovaSports com a marca do seu cliente - sem programar."
$sub.ForeColor = $C_SUB; $sub.Location = New-Object Drawing.Point(27, 52); $sub.AutoSize = $true
$form.Controls.Add($sub)

$y = 90
function Add-Row([string]$label, $control, [string]$hint = "") {
    $l = New-Object Windows.Forms.Label
    $l.Text = $label; $l.Font = $fontB; $l.Location = New-Object Drawing.Point(28, ($script:y + 4)); $l.Size = New-Object Drawing.Size(170, 24)
    $form.Controls.Add($l)
    $control.Location = New-Object Drawing.Point(200, $script:y)
    if ($control.Width -lt 50) { $control.Width = 420 }
    $control.BackColor = $C_CARD; $control.ForeColor = $C_TXT
    $form.Controls.Add($control)
    if ($hint) {
        $h = New-Object Windows.Forms.Label
        $h.Text = $hint; $h.ForeColor = $C_SUB; $h.AutoSize = $true
        $h.Location = New-Object Drawing.Point(200, ($script:y + 28))
        $form.Controls.Add($h)
        $script:y += 18
    }
    $script:y += 38
}

function New-Text([int]$w = 420) { $t = New-Object Windows.Forms.TextBox; $t.Width = $w; $t.BorderStyle = "FixedSingle"; return $t }

$cbProfile = New-Object Windows.Forms.ComboBox; $cbProfile.DropDownStyle = "DropDownList"; $cbProfile.Width = 420
Add-Row "Cliente salvo" $cbProfile "Escolha para carregar um app ja gerado (gera a proxima versao)."
$cbApp = New-Object Windows.Forms.ComboBox; $cbApp.DropDownStyle = "DropDownList"; $cbApp.Width = 420
foreach ($k in $Models.Keys) { [void]$cbApp.Items.Add($k) }; $cbApp.SelectedIndex = 0
$appHint = $null
Add-Row "Modelo do app" $cbApp $Models["NovaTV"].desc
$appHint = $form.Controls[$form.Controls.Count - 1]
$cbApp.Add_SelectedIndexChanged({ if ($appHint) { $appHint.Text = $Models[[string]$cbApp.SelectedItem].desc } })
$tLabel = New-Text; Add-Row "Nome do app" $tLabel "Como aparece no celular/TV (max. 30 letras)."
$tId = New-Text; Add-Row "Pacote" $tId "Unico por app. Ex.: com.minhatv.app (letras minusculas, numeros e pontos)."
$tPanel = New-Text; Add-Row "URL do painel" $tPanel "Ex.: https://p2pwmax.dexdown.shop  (vazio = padrao)"
$tKey = New-Text; Add-Row "Chave do painel" $tKey
$tRes = New-Text; Add-Row "Codigo revenda" $tRes

function Add-FileRow([string]$label, [string]$filter) {
    $t = New-Text 330
    $b = New-Object Windows.Forms.Button
    $b.Text = "Escolher..."; $b.FlatStyle = "Flat"; $b.ForeColor = $C_TXT; $b.BackColor = $C_CARD
    $b.Location = New-Object Drawing.Point(540, ($script:y - 1)); $b.Size = New-Object Drawing.Size(80, 26)
    $b.Add_Click({
        $d = New-Object Windows.Forms.OpenFileDialog
        $d.Filter = "Imagens|*.png;*.jpg;*.jpeg;*.webp;*.bmp"
        if ($d.ShowDialog() -eq "OK") { $this.Tag.Text = $d.FileName }
    })
    $b.Tag = $t
    $form.Controls.Add($b)
    Add-Row $label $t
    return $t
}
$tLogo = Add-FileRow "Logo (PNG)"
$tIcon = Add-FileRow "Icone quadrado"
$tBg = Add-FileRow "Fundo (JPG/PNG)"

$tColor = New-Text 120; $tColor.Text = "#0B1020"
$bColor = New-Object Windows.Forms.Button
$bColor.Text = "Cor..."; $bColor.FlatStyle = "Flat"; $bColor.Location = New-Object Drawing.Point(330, ($y - 1)); $bColor.Size = New-Object Drawing.Size(70, 26)
$bColor.Add_Click({
    $d = New-Object Windows.Forms.ColorDialog
    if ($d.ShowDialog() -eq "OK") { $tColor.Text = "#{0:X2}{1:X2}{2:X2}" -f $d.Color.R, $d.Color.G, $d.Color.B }
})
$form.Controls.Add($bColor)
Add-Row "Cor do icone/banner" $tColor
$chkPlay = New-Object Windows.Forms.CheckBox
$chkPlay.Text = "Gerar tambem para Google Play (.aab) - so apps Android"; $chkPlay.Width = 420; $chkPlay.Height = 26
Add-Row "Google Play" $chkPlay

# pre-visualizacao do icone
$preview = New-Object Windows.Forms.PictureBox
$preview.Location = New-Object Drawing.Point(660, 90); $preview.Size = New-Object Drawing.Size(260, 260)
$preview.SizeMode = "Zoom"; $preview.BackColor = $C_CARD
$form.Controls.Add($preview)
$pvLabel = New-Object Windows.Forms.Label
$pvLabel.Text = "Previa do icone"; $pvLabel.ForeColor = $C_SUB; $pvLabel.AutoSize = $true
$pvLabel.Location = New-Object Drawing.Point(660, 356)
$form.Controls.Add($pvLabel)
function Update-Preview {
    try {
        $src = $tIcon.Text; if (-not $src) { $src = $tLogo.Text }
        if ($preview.Image) { $preview.Image.Dispose(); $preview.Image = $null }
        if ($src -and (Test-Path $src)) {
            $c = [Drawing.ColorTranslator]::FromHtml($tColor.Text)
            $img = Load-Image $src
            if ($tIcon.Text) { $preview.Image = Fit-Into $img 256 256 0 $null } else { $preview.Image = Fit-Into $img 256 256 0.1 $c }
        }
    } catch { }
}
foreach ($t in @($tLogo, $tIcon, $tColor)) { $t.Add_TextChanged({ Update-Preview }) }

$log = New-Object Windows.Forms.TextBox
$log.Multiline = $true; $log.ReadOnly = $true; $log.ScrollBars = "Vertical"
$log.BackColor = [Drawing.Color]::FromArgb(8, 10, 20); $log.ForeColor = [Drawing.Color]::FromArgb(120, 230, 160)
$log.Font = New-Object Drawing.Font("Consolas", 9)
$log.Location = New-Object Drawing.Point(28, ($y + 8)); $log.Size = New-Object Drawing.Size(892, 150)
$log.Anchor = "Top,Left,Right"
$form.Controls.Add($log)

$bGo = New-Object Windows.Forms.Button
$bGo.Text = "GERAR APK"; $bGo.Font = $fontB; $bGo.FlatStyle = "Flat"
$bGo.BackColor = $C_ACC; $bGo.ForeColor = [Drawing.Color]::White
$bGo.Location = New-Object Drawing.Point(660, 390); $bGo.Size = New-Object Drawing.Size(260, 48)
$form.Controls.Add($bGo)
$bSetup = New-Object Windows.Forms.Button
$bSetup.Text = "Instalar / atualizar ferramentas"; $bSetup.FlatStyle = "Flat"
$bSetup.Location = New-Object Drawing.Point(660, 494); $bSetup.Size = New-Object Drawing.Size(260, 32)
$form.Controls.Add($bSetup)
$bOpen = New-Object Windows.Forms.Button
$bOpen.Text = "Abrir pasta SAIDA"; $bOpen.FlatStyle = "Flat"
$bOpen.Location = New-Object Drawing.Point(660, 570); $bOpen.Size = New-Object Drawing.Size(260, 32)
$bOpen.Add_Click({ Start-Process explorer.exe $Out })
$form.Controls.Add($bOpen)
$bAll = New-Object Windows.Forms.Button
$bAll.Text = "GERAR TODOS OS MODELOS (6)"; $bAll.Font = $fontB; $bAll.FlatStyle = "Flat"
$bAll.BackColor = [Drawing.Color]::FromArgb(120, 70, 220); $bAll.ForeColor = [Drawing.Color]::White
$bAll.Location = New-Object Drawing.Point(660, 444); $bAll.Size = New-Object Drawing.Size(260, 40)
$form.Controls.Add($bAll)
$bUpdate = New-Object Windows.Forms.Button
$bUpdate.Text = "Atualizar o laboratorio"; $bUpdate.FlatStyle = "Flat"
$bUpdate.Location = New-Object Drawing.Point(660, 532); $bUpdate.Size = New-Object Drawing.Size(260, 32)
$form.Controls.Add($bUpdate)

function Log([string]$m) { $log.AppendText($m + "`r`n") }

function Load-Profiles {
    $cbProfile.Items.Clear(); [void]$cbProfile.Items.Add("(novo cliente)")
    Get-ChildItem $Prof -Filter *.json | Sort-Object LastWriteTime -Descending | ForEach-Object { [void]$cbProfile.Items.Add($_.BaseName) }
    $cbProfile.SelectedIndex = 0
}
$cbProfile.Add_SelectedIndexChanged({
    if ($cbProfile.SelectedIndex -le 0) { return }
    $p = Get-Content (Join-Path $Prof ($cbProfile.SelectedItem + ".json")) -Raw -Encoding UTF8 | ConvertFrom-Json
    $cbApp.SelectedItem = $p.app; $tLabel.Text = $p.label; $tId.Text = $p.id; $tPanel.Text = $p.panel
    $tKey.Text = $p.key; $tRes.Text = $p.reseller; $tLogo.Text = $p.logo; $tIcon.Text = $p.icon
    $tBg.Text = $p.background; $tColor.Text = $p.color
    $chkPlay.Checked = ($p.play -eq $true)
})

# tarefa em segundo plano: outro PowerShell gravando num log; a janela le o log a cada 0,5 s
$script:proc = $null; $script:logFile = $null; $script:logPos = 0; $script:onDone = $null
$timer = New-Object Windows.Forms.Timer; $timer.Interval = 500
$timer.Add_Tick({
    if (-not $script:logFile) { return }
    # le o estado ANTES do log: assim as ultimas linhas (com o resultado) nunca se perdem
    $exited = ($script:proc -and $script:proc.HasExited)
    if (Test-Path $script:logFile) {
        try {
            $fs = [IO.File]::Open($script:logFile, "Open", "Read", "ReadWrite")
            $fs.Seek($script:logPos, "Begin") | Out-Null
            $sr = New-Object IO.StreamReader($fs)
            $txt = $sr.ReadToEnd(); $script:logPos = $fs.Position
            $sr.Close()
            if ($txt) {
                foreach ($line in ($txt -split "`r?`n")) {
                    if (-not $line) { continue }
                    if ($line -match "@@OK@@") { $script:result = "ok"; continue }
                    if ($line -match "@@FALHOU@@") { $script:result = "erro"; continue }
                    Log $line
                }
            }
        } catch { }
    }
    if ($exited) {
        $timer.Stop(); $script:logFile = $null
        $bGo.Enabled = $true; $bSetup.Enabled = $true; $bAll.Enabled = $true; $bUpdate.Enabled = $true
        if ($script:onDone) { & $script:onDone $script:result }
    }
})

function Run-Task([string]$task, [string]$arg, $onDone) {
    $script:logFile = Join-Path $LogDir ("{0}-{1}.log" -f $task, (Get-Date -Format "yyyyMMdd-HHmmss"))
    $script:logPos = 0; $script:result = "erro"; $script:onDone = $onDone
    $bGo.Enabled = $false; $bSetup.Enabled = $false; $bAll.Enabled = $false; $bUpdate.Enabled = $false
    $argLine = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Task $task -LogFile `"$($script:logFile)`""
    if ($arg) { $argLine += " -Arg `"$arg`"" }
    $script:proc = Start-Process powershell.exe -ArgumentList $argLine -WindowStyle Hidden -PassThru
    $timer.Start()
}

$bSetup.Add_Click({
    Log "Instalando ferramentas (Java 17 + Android SDK). Pode levar 5 a 15 minutos na 1a vez..."
    Run-Task "setup" "" { param($r) if ($r -eq "ok") { Log "Ferramentas OK." } else { Log "Falhou: veja as mensagens acima." } }
})

# confere os campos e salva o cliente; devolve o arquivo do perfil (ou $null se faltou algo)
function Save-FormProfile {
    $label = $tLabel.Text.Trim(); $id = $tId.Text.Trim().ToLower(); $panel = $tPanel.Text.Trim(); $color = $tColor.Text.Trim()
    $err = $null
    if (-not $label) { $err = "Informe o nome do app." }
    elseif ($label.Length -gt 30) { $err = "Nome do app muito longo (max. 30)." }
    elseif ($label -match "['`"\\<>&@?]") { $err = "O nome nao pode ter aspas, apostrofo, \, <, >, &, @ ou ?." }
    elseif ($id -notmatch "^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$") { $err = "Pacote invalido. Exemplo: com.minhatv.app" }
    elseif ($panel -and $panel -notmatch "^https?://\S+$") { $err = "A URL do painel deve comecar com http:// ou https://" }
    elseif ($color -notmatch "^#[0-9A-Fa-f]{6}$") { $err = "Cor no formato #RRGGBB (ex.: #0B1020)." }
    else { foreach ($f in @($tLogo.Text, $tIcon.Text, $tBg.Text)) { if ($f -and -not (Test-Path $f)) { $err = "Arquivo nao encontrado: $f" } } }
    if ($err) { [Windows.Forms.MessageBox]::Show($err, "Laboratorio", "OK", "Warning") | Out-Null; return $null }

    $pf = Join-Path $Prof ($id + ".json")
    $ver = 1
    if (Test-Path $pf) { try { $ver = [int]((Get-Content $pf -Raw -Encoding UTF8 | ConvertFrom-Json).version) + 1 } catch { $ver = 1 } }
    $p = [ordered]@{
        app = [string]$cbApp.SelectedItem; label = $label; id = $id; panel = $panel; key = $tKey.Text.Trim()
        reseller = $tRes.Text.Trim(); logo = $tLogo.Text.Trim(); icon = $tIcon.Text.Trim(); background = $tBg.Text.Trim()
        color = $color; version = $ver; play = [bool]$chkPlay.Checked
    }
    [IO.File]::WriteAllText($pf, ($p | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
    return $pf
}

$bGo.Add_Click({
    $pf = Save-FormProfile
    if (-not $pf) { return }
    $log.Clear()
    Log ("Gerando '" + $tLabel.Text.Trim() + "' (" + [string]$cbApp.SelectedItem + ")...")
    Run-Task "build" $pf {
        param($r)
        if ($r -eq "ok") {
            Load-Profiles
            [Windows.Forms.MessageBox]::Show("APK pronto na pasta SAIDA!", "Laboratorio", "OK", "Information") | Out-Null
            Start-Process explorer.exe $Out
        } else {
            [Windows.Forms.MessageBox]::Show("Nao deu certo. Veja as mensagens na parte de baixo da janela.", "Laboratorio", "OK", "Error") | Out-Null
        }
    }
})

$form.Add_Shown({
    Load-Profiles
    if (-not (Tools-Ready)) {
        Log "Bem-vindo! Na primeira vez o laboratorio baixa o Java 17 e o Android SDK (~600 MB)."
        Log "Clique em 'Instalar / atualizar ferramentas' (ou ja clique em GERAR APK, que ele instala antes)."
    } else {
        Log "Ferramentas prontas. Preencha os dados e clique em GERAR APK."
    }
})
$bAll.Add_Click({
    $pf = Save-FormProfile
    if (-not $pf) { return }
    $q = [Windows.Forms.MessageBox]::Show("Gerar os 6 modelos (NovaTV, NovaSports, NovaKids e Roku TV/Kids/Esportes) para '" + $tLabel.Text.Trim() + "'?`r`nPode levar de 5 a 15 minutos.", "Laboratorio", "YesNo", "Question")
    if ($q -ne "Yes") { return }
    $log.Clear()
    Log "Gerando todos os modelos..."
    Run-Task "buildall" $pf {
        param($r)
        Load-Profiles
        if ($r -eq "ok") {
            [Windows.Forms.MessageBox]::Show("Todos os apps prontos na pasta SAIDA!", "Laboratorio", "OK", "Information") | Out-Null
        } else {
            [Windows.Forms.MessageBox]::Show("Alguns modelos falharam (veja as mensagens). Os que deram certo estao na pasta SAIDA.", "Laboratorio", "OK", "Warning") | Out-Null
        }
        Start-Process explorer.exe $Out
    }
})

$bUpdate.Add_Click({
    $q = [Windows.Forms.MessageBox]::Show("Baixar e instalar a versao mais nova do laboratorio?`r`nO que for substituido vai para ferramentas\backup. Clientes, chave e APKs ficam.", "Laboratorio", "YesNo", "Question")
    if ($q -ne "Yes") { return }
    $log.Clear()
    Run-Task "selfupdate" "" {
        param($r)
        if ($r -eq "ok") {
            [Windows.Forms.MessageBox]::Show("Laboratorio atualizado! Feche e abra o LABORATORIO.bat de novo.", "Laboratorio", "OK", "Information") | Out-Null
            $form.Close()
        } else {
            [Windows.Forms.MessageBox]::Show("Nao deu para atualizar agora (veja as mensagens). Se algo foi trocado, a copia esta em ferramentas\\backup.", "Laboratorio", "OK", "Warning") | Out-Null
        }
    }
})

# teste automatico: monta a janela e sai sem mostrar
if ($Task -eq "guitest") { Load-Profiles; Update-Preview; $form.Dispose(); Write-Host "@@GUI-OK@@"; exit 0 }
[void]$form.ShowDialog()
