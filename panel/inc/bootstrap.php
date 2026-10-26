<?php
declare(strict_types=1);

define('APP_ROOT', dirname(__DIR__));
define('DATA_DIR', APP_ROOT . '/data');
define('UPLOAD_DIR', APP_ROOT . '/uploads');
define('PANEL_VERSION', '1.1.0');
define('SESSION_IDLE', 30 * 60);       // desloga após 30 min sem uso
define('SESSION_MAX', 8 * 3600);       // e no máximo 8h por login

ini_set('display_errors', '0');
ini_set('expose_php', '0');
error_reporting(E_ALL);
date_default_timezone_set('America/Sao_Paulo');
header_remove('X-Powered-By');

function is_https(): bool
{
    return (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
        || (($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https')
        || (($_SERVER['SERVER_PORT'] ?? '') === '443');
}

if (session_status() === PHP_SESSION_NONE && PHP_SAPI !== 'cli' && !defined('NO_SESSION')) {
    ini_set('session.use_strict_mode', '1');
    ini_set('session.use_only_cookies', '1');
    ini_set('session.cookie_httponly', '1');
    session_name('NTVPANEL');
    session_set_cookie_params([
        'lifetime' => 0,
        'path' => '/',
        'secure' => is_https(),
        'httponly' => true,
        'samesite' => 'Strict',
    ]);
    session_start();
    security_headers();
}

/** Cabeçalhos contra clickjacking, XSS, sniffing e vazamento de referer. */
function security_headers(): void
{
    if (headers_sent()) return;
    header("Content-Security-Policy: default-src 'self'; img-src 'self' data: https: http:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'self'; object-src 'none'");
    header('X-Frame-Options: DENY');
    header('X-Content-Type-Options: nosniff');
    header('Referrer-Policy: same-origin');
    header('Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()');
    header('Cross-Origin-Opener-Policy: same-origin');
    header('Cache-Control: no-store, private');
    if (is_https()) header('Strict-Transport-Security: max-age=31536000');
}

/* ------------------------------------------------------------------ DB */

function db(): PDO
{
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }
    if (!is_dir(DATA_DIR)) {
        mkdir(DATA_DIR, 0775, true);
    }
    // nome aleatório do banco (proteção extra caso o .htaccess não seja respeitado)
    $nameFile = DATA_DIR . '/dbname.php';
    $dbName = is_file($nameFile) ? (string)include $nameFile : '';
    if (!preg_match('/^painel_[a-f0-9]{24}\.sqlite$/', $dbName)) {
        $dbName = 'painel_' . bin2hex(random_bytes(12)) . '.sqlite';
        file_put_contents($nameFile, "<?php return '" . $dbName . "';\n");
    }
    $pdo = new PDO('sqlite:' . DATA_DIR . '/' . $dbName);
    $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    $pdo->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);
    $pdo->setAttribute(PDO::ATTR_EMULATE_PREPARES, false);
    $pdo->exec('PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;');
    migrate($pdo);
    return $pdo;
}

function column_exists(PDO $pdo, string $table, string $col): bool
{
    foreach ($pdo->query("PRAGMA table_info($table)") as $c) {
        if ($c['name'] === $col) return true;
    }
    return false;
}

function migrate(PDO $pdo): void
{
    $pdo->exec("
        CREATE TABLE IF NOT EXISTS admins (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
        );
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT
        );
        CREATE TABLE IF NOT EXISTS resellers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            username TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            code TEXT NOT NULL UNIQUE,
            max_devices INTEGER NOT NULL DEFAULT 50,
            max_servers INTEGER NOT NULL DEFAULT 5,
            expires_at TEXT NOT NULL DEFAULT '',
            active INTEGER NOT NULL DEFAULT 1,
            whatsapp TEXT NOT NULL DEFAULT '',
            notes TEXT NOT NULL DEFAULT '',
            last_login TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
        );
        CREATE TABLE IF NOT EXISTS servers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            type TEXT NOT NULL DEFAULT 'xtream',
            urls TEXT NOT NULL DEFAULT '',
            m3u_url TEXT NOT NULL DEFAULT '',
            epg_url TEXT NOT NULL DEFAULT '',
            user_agent TEXT NOT NULL DEFAULT '',
            output TEXT NOT NULL DEFAULT 'ts',
            p2p_email TEXT NOT NULL DEFAULT '',
            p2p_lib TEXT NOT NULL DEFAULT '',
            p2p_template TEXT NOT NULL DEFAULT '',
            priority INTEGER NOT NULL DEFAULT 0,
            active INTEGER NOT NULL DEFAULT 1,
            notes TEXT NOT NULL DEFAULT '',
            reseller_id INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
        );
        CREATE TABLE IF NOT EXISTS banners (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL DEFAULT '',
            subtitle TEXT NOT NULL DEFAULT '',
            image TEXT NOT NULL DEFAULT '',
            action_type TEXT NOT NULL DEFAULT 'none',
            action_value TEXT NOT NULL DEFAULT '',
            position INTEGER NOT NULL DEFAULT 0,
            active INTEGER NOT NULL DEFAULT 1
        );
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            body TEXT NOT NULL DEFAULT '',
            popup INTEGER NOT NULL DEFAULT 0,
            active INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
        );
        CREATE TABLE IF NOT EXISTS devices (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL UNIQUE,
            model TEXT NOT NULL DEFAULT '',
            platform TEXT NOT NULL DEFAULT '',
            android TEXT NOT NULL DEFAULT '',
            app_version TEXT NOT NULL DEFAULT '',
            username TEXT NOT NULL DEFAULT '',
            server_id INTEGER NOT NULL DEFAULT 0,
            ip TEXT NOT NULL DEFAULT '',
            blocked INTEGER NOT NULL DEFAULT 0,
            reseller_id INTEGER NOT NULL DEFAULT 0,
            first_seen TEXT NOT NULL DEFAULT (datetime('now','localtime')),
            last_seen TEXT NOT NULL DEFAULT (datetime('now','localtime'))
        );
        CREATE TABLE IF NOT EXISTS login_attempts (
            ip TEXT NOT NULL,
            username TEXT NOT NULL DEFAULT '',
            ts INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS api_hits (
            ip TEXT NOT NULL,
            minute INTEGER NOT NULL,
            hits INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY (ip, minute)
        );
        CREATE TABLE IF NOT EXISTS app_errors (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL DEFAULT '',
            model TEXT NOT NULL DEFAULT '',
            android TEXT NOT NULL DEFAULT '',
            app_version TEXT NOT NULL DEFAULT '',
            summary TEXT NOT NULL DEFAULT '',
            trace TEXT NOT NULL DEFAULT '',
            ip TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
        );
        CREATE TABLE IF NOT EXISTS audit_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            who TEXT NOT NULL,
            action TEXT NOT NULL,
            details TEXT NOT NULL DEFAULT '',
            ip TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
        );
    ");
    // migrações de versões anteriores
    if (!column_exists($pdo, 'servers', 'reseller_id')) $pdo->exec("ALTER TABLE servers ADD COLUMN reseller_id INTEGER NOT NULL DEFAULT 0");
    if (!column_exists($pdo, 'devices', 'reseller_id')) $pdo->exec("ALTER TABLE devices ADD COLUMN reseller_id INTEGER NOT NULL DEFAULT 0");
    if (!column_exists($pdo, 'login_attempts', 'username')) $pdo->exec("ALTER TABLE login_attempts ADD COLUMN username TEXT NOT NULL DEFAULT ''");
    $pdo->exec("
        CREATE INDEX IF NOT EXISTS idx_attempts_ip ON login_attempts(ip, ts);
        CREATE INDEX IF NOT EXISTS idx_devices_reseller ON devices(reseller_id, blocked);
        CREATE INDEX IF NOT EXISTS idx_servers_reseller ON servers(reseller_id, active);
    ");

    $ins = $pdo->prepare('INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)');
    foreach (default_settings() as $k => $v) {
        $ins->execute([$k, (string)$v]);
    }
    // API key gerada uma única vez
    $stmt = $pdo->query("SELECT value FROM settings WHERE key='api_key'");
    $key = $stmt ? $stmt->fetchColumn() : '';
    if (!$key) {
        $pdo->prepare("UPDATE settings SET value=? WHERE key='api_key'")->execute([bin2hex(random_bytes(16))]);
    }
}

function default_settings(): array
{
    return [
        // Identidade
        'app_name' => 'NovaTV',
        'logo' => '',
        'background_image' => '',
        'background_mode' => 'gradient',  // gradient | image | solid
        'splash_text' => 'Carregando sua diversão...',
        'welcome_message' => '',
        'support_text' => 'Suporte: WhatsApp (00) 00000-0000',
        // Cores (estilo vermelho padrão)
        'color_primary' => '#E1061B',
        'color_accent' => '#FFFFFF',
        'color_bg1' => '#8E0A16',
        'color_bg2' => '#2B0206',
        'color_surface' => '#000000',
        'surface_opacity' => '35',
        'color_text' => '#FFFFFF',
        'color_text2' => '#F3C9CD',
        'color_focus_text' => '#E1061B',
        'color_kids' => '#FFB300',
        'corner_radius' => '12',
        'ui_scale' => '100',
        // Abas
        'tab_highlights' => '1', 'label_highlights' => 'INÍCIO',
        'tab_tv' => '1', 'label_tv' => 'TV',
        'tab_sports' => '1', 'label_sports' => 'JOGOS',
        'tab_movies' => '1', 'label_movies' => 'FILMES',
        'tab_series' => '1', 'label_series' => 'SÉRIES',
        'tab_kids' => '1', 'label_kids' => 'KIDS',
        'tab_anime' => '1', 'label_anime' => 'ANIME',
        'tab_favorites' => '1', 'label_favorites' => 'FAVORITOS',
        'start_tab' => 'highlights',
        'icon_tv' => '', 'icon_sports' => '', 'icon_highlights' => '', 'icon_movies' => '',
        'icon_series' => '', 'icon_kids' => '', 'icon_anime' => '', 'icon_favorites' => '',
        'home_mosaic' => '1',
        'tab_icon_only' => '1',
        // Kids / Anime / Adulto
        'kids_keywords' => 'kids, infantil, infantis, criança, crianca, desenho, desenhos, cartoon, disney, nick, nickelodeon, discovery kids, gloob, cartoonito, animação, animacao, family, família, familia',
        'sports_keywords' => 'esporte, esportes, sport, sports, futebol, premiere, espn, sportv, combate, ufc, nba, nfl, f1, formula, brasileirão, brasileirao, libertadores, champions, copa',
        'anime_keywords' => 'anime, animes, crunchyroll, otaku',
        'adult_keywords' => 'adult, adulto, adultos, xxx, +18, 18+, hot, porn, sexy',
        'hide_adult' => '1',
        'parental_pin' => '0000',
        'kids_lock' => '0',
        // Player
        'player_buffer' => 'normal', // low | normal | high
        'default_user_agent' => '',
        'epg_enabled' => '1',
        // Jogos do dia
        'games_enabled' => '1',
        'games_api_url' => 'https://xsender.painelmaster.lol/retornojogosmx.json',
        'games_cache_minutes' => '10',
        'games_map' => '',
        // TMDB
        'tmdb_enabled' => '1',
        'tmdb_api_key' => '',
        'tmdb_token' => '',
        'tmdb_language' => 'pt-BR',
        // Revendas
        'ask_reseller_code' => '0',
        'require_reseller_code' => '0',
        // Sistema
        'maintenance' => '0',
        'maintenance_message' => 'Estamos em manutenção. Volte em alguns minutos.',
        'latest_version_code' => '1',
        'update_url' => '',
        'force_update' => '0',
        'allow_server_select' => '1',
        'register_devices' => '1',
        'api_key' => '',
        'api_key_required' => '0',
        'api_rate_limit' => '120',
    ];
}

/* ------------------------------------------------------------ settings */

function settings(bool $refresh = false): array
{
    static $cache = null;
    if ($cache === null || $refresh) {
        $cache = default_settings();
        foreach (db()->query('SELECT key, value FROM settings') as $row) {
            $cache[$row['key']] = $row['value'];
        }
    }
    return $cache;
}

function setting(string $key, string $default = ''): string
{
    $s = settings();
    return isset($s[$key]) ? (string)$s[$key] : $default;
}

function save_settings(array $values): void
{
    $stmt = db()->prepare('INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value');
    foreach ($values as $k => $v) {
        $stmt->execute([$k, (string)$v]);
    }
    settings(true);
}

/* ------------------------------------------------------------- helpers */

function e(?string $s): string
{
    return htmlspecialchars((string)$s, ENT_QUOTES, 'UTF-8');
}

function base_url(): string
{
    $host = $_SERVER['HTTP_HOST'] ?? 'localhost';
    // evita Host header injection: só caracteres válidos de host
    if (!preg_match('/^[a-z0-9.\-:\[\]]+$/i', $host)) $host = 'localhost';
    $docRoot = realpath($_SERVER['DOCUMENT_ROOT'] ?? '') ?: '';
    $root = realpath(APP_ROOT) ?: APP_ROOT;
    if ($docRoot !== '' && strpos($root, $docRoot) === 0) {
        $path = str_replace('\\', '/', substr($root, strlen($docRoot)));
    } else {
        $path = rtrim(str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'] ?? '/')), '/');
        $path = preg_replace('#/api$#', '', $path);
    }
    return (is_https() ? 'https' : 'http') . '://' . $host . rtrim((string)$path, '/');
}

function asset_url(string $path): string
{
    if ($path === '') return '';
    if (preg_match('#^https?://#i', $path)) return $path;
    return base_url() . '/' . ltrim($path, '/');
}

function redirect(string $to): void
{
    header('Location: ' . $to);
    exit;
}

function flash(string $msg, string $type = 'ok'): void
{
    $_SESSION['flash'][] = ['msg' => $msg, 'type' => $type];
}

function take_flashes(): array
{
    $f = $_SESSION['flash'] ?? [];
    unset($_SESSION['flash']);
    return $f;
}

function client_ip(): string
{
    $ip = $_SERVER['HTTP_CF_CONNECTING_IP'] ?? $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';
    return filter_var($ip, FILTER_VALIDATE_IP) ? $ip : '0.0.0.0';
}

function is_hex_color(string $c): bool
{
    return (bool)preg_match('/^#[0-9a-fA-F]{6}$/', $c);
}

function keywords(string $csv): array
{
    $out = [];
    foreach (explode(',', $csv) as $k) {
        $k = trim(mb_strtolower($k));
        if ($k !== '') $out[] = $k;
    }
    return array_values(array_unique($out));
}

function lines(string $text): array
{
    $out = [];
    foreach (preg_split('/\r\n|\r|\n/', $text) as $l) {
        $l = trim($l);
        if ($l !== '') $out[] = rtrim($l, '/');
    }
    return array_values(array_unique($out));
}

function post_str(string $k, int $max = 255): string
{
    $v = $_POST[$k] ?? '';
    return is_string($v) ? mb_substr(trim($v), 0, $max) : '';
}

/* ---------------------------------------------------------------- CSRF */

function csrf_token(): string
{
    if (empty($_SESSION['csrf'])) {
        $_SESSION['csrf'] = bin2hex(random_bytes(20));
    }
    return $_SESSION['csrf'];
}

function csrf_field(): string
{
    return '<input type="hidden" name="_csrf" value="' . e(csrf_token()) . '">';
}

function csrf_check(): void
{
    if ($_SERVER['REQUEST_METHOD'] === 'POST') {
        $t = $_POST['_csrf'] ?? '';
        if (!is_string($t) || !hash_equals(csrf_token(), $t)) {
            http_response_code(400);
            exit('Sessão expirada. Volte e recarregue a página.');
        }
    }
}

/* ---------------------------------------------------------------- auth */

function admin_count(): int
{
    return (int)db()->query('SELECT COUNT(*) FROM admins')->fetchColumn();
}

function is_admin(): bool
{
    return ($_SESSION['role'] ?? '') === 'admin';
}

/** 0 para admin; id da revenda logada. */
function reseller_id(): int
{
    return is_admin() ? 0 : (int)($_SESSION['reseller_id'] ?? 0);
}

function current_user(): string
{
    return (string)($_SESSION['user'] ?? '');
}

function session_fingerprint(): string
{
    return hash('sha256', ($_SERVER['HTTP_USER_AGENT'] ?? '') . '|' . ($_SERVER['HTTP_ACCEPT_LANGUAGE'] ?? ''));
}

function login_as(string $role, int $id, string $user, int $resellerId = 0): void
{
    session_regenerate_id(true);
    $_SESSION['role'] = $role;
    $_SESSION['uid'] = $id;
    $_SESSION['user'] = $user;
    $_SESSION['reseller_id'] = $resellerId;
    $_SESSION['login_at'] = time();
    $_SESSION['seen_at'] = time();
    $_SESSION['fp'] = session_fingerprint();
    unset($_SESSION['csrf']);
}

function logout_now(): void
{
    $_SESSION = [];
    if (ini_get('session.use_cookies')) {
        $p = session_get_cookie_params();
        setcookie(session_name(), '', time() - 42000, $p['path'], $p['domain'] ?? '', (bool)$p['secure'], true);
    }
    session_destroy();
}

function require_login(): void
{
    if (admin_count() === 0) {
        redirect('setup.php');
    }
    if (empty($_SESSION['role'])) {
        redirect('login.php');
    }
    $now = time();
    $expired = $now - (int)($_SESSION['login_at'] ?? 0) > SESSION_MAX
        || $now - (int)($_SESSION['seen_at'] ?? 0) > SESSION_IDLE;
    // sessão roubada (outro navegador) ou expirada
    if ($expired || !hash_equals((string)($_SESSION['fp'] ?? ''), session_fingerprint())) {
        logout_now();
        session_start();
        flash($expired ? 'Sua sessão expirou. Entre novamente.' : 'Sessão inválida. Entre novamente.', 'warn');
        redirect('login.php');
    }
    // revenda desativada/expirada perde o acesso na hora
    if (!is_admin()) {
        $r = reseller(reseller_id());
        if (!$r || !$r['active'] || reseller_expired($r)) {
            logout_now();
            session_start();
            flash('Acesso de revenda inativo ou vencido. Fale com o administrador.', 'err');
            redirect('login.php');
        }
    }
    $_SESSION['seen_at'] = $now;
    // troca o id de sessão periodicamente
    if ($now - (int)($_SESSION['rotated_at'] ?? 0) > 900) {
        session_regenerate_id(true);
        $_SESSION['rotated_at'] = $now;
    }
    csrf_check();
}

function require_admin(): void
{
    require_login();
    if (!is_admin()) {
        http_response_code(403);
        flash('Somente o administrador pode acessar essa área.', 'err');
        redirect('index.php');
    }
}

/** Limite de tentativas: por IP e por usuário (impede força bruta distribuída). */
function too_many_attempts(string $ip, string $username = ''): bool
{
    $pdo = db();
    $pdo->prepare('DELETE FROM login_attempts WHERE ts < ?')->execute([time() - 1800]);
    $st = $pdo->prepare('SELECT COUNT(*) FROM login_attempts WHERE ip=? AND ts > ?');
    $st->execute([$ip, time() - 900]);
    if ((int)$st->fetchColumn() >= 8) return true;
    if ($username !== '') {
        $st = $pdo->prepare('SELECT COUNT(*) FROM login_attempts WHERE username=? AND ts > ?');
        $st->execute([mb_strtolower($username), time() - 1800]);
        if ((int)$st->fetchColumn() >= 15) return true;
    }
    return false;
}

function register_attempt(string $ip, string $username = ''): void
{
    db()->prepare('INSERT INTO login_attempts (ip, username, ts) VALUES (?, ?, ?)')->execute([$ip, mb_strtolower($username), time()]);
}

function clear_attempts(string $ip): void
{
    db()->prepare('DELETE FROM login_attempts WHERE ip=?')->execute([$ip]);
}

function audit(string $action, string $details = ''): void
{
    try {
        $who = (is_admin() ? 'admin:' : 'revenda:') . current_user();
        db()->prepare('INSERT INTO audit_log (who, action, details, ip) VALUES (?, ?, ?, ?)')
            ->execute([$who, $action, mb_substr($details, 0, 500), client_ip()]);
    } catch (Throwable $e) {
    }
}

function password_ok(string $p): ?string
{
    if (strlen($p) < 8) return 'A senha precisa de pelo menos 8 caracteres.';
    if (!preg_match('/[A-Za-z]/', $p) || !preg_match('/\d/', $p)) return 'Use letras e números na senha.';
    return null;
}

/* ------------------------------------------------------------ revendas */

function reseller(int $id): ?array
{
    if ($id <= 0) return null;
    $st = db()->prepare('SELECT * FROM resellers WHERE id=?');
    $st->execute([$id]);
    return $st->fetch() ?: null;
}

function reseller_by_code(string $code): ?array
{
    if ($code === '') return null;
    $st = db()->prepare('SELECT * FROM resellers WHERE code=? COLLATE NOCASE');
    $st->execute([$code]);
    return $st->fetch() ?: null;
}

function reseller_expired(array $r): bool
{
    return $r['expires_at'] !== '' && strtotime($r['expires_at'] . ' 23:59:59') < time();
}

function reseller_device_count(int $rid): int
{
    $st = db()->prepare('SELECT COUNT(*) FROM devices WHERE reseller_id=? AND blocked=0');
    $st->execute([$rid]);
    return (int)$st->fetchColumn();
}

function new_reseller_code(): string
{
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    do {
        $c = '';
        for ($i = 0; $i < 6; $i++) $c .= $alphabet[random_int(0, strlen($alphabet) - 1)];
    } while (reseller_by_code($c));
    return $c;
}

/* ------------------------------------------------------------- uploads */

/** Retorna caminho relativo (uploads/xxx.png) ou null. */
function handle_upload(string $field, array &$errors): ?string
{
    if (empty($_FILES[$field]) || ($_FILES[$field]['error'] ?? UPLOAD_ERR_NO_FILE) === UPLOAD_ERR_NO_FILE) {
        return null;
    }
    $f = $_FILES[$field];
    if ($f['error'] !== UPLOAD_ERR_OK) {
        $errors[] = 'Falha no upload (código ' . (int)$f['error'] . ').';
        return null;
    }
    if ($f['size'] > 6 * 1024 * 1024) {
        $errors[] = 'Imagem muito grande (máx. 6 MB).';
        return null;
    }
    $info = @getimagesize($f['tmp_name']);
    $map = [IMAGETYPE_PNG => 'png', IMAGETYPE_JPEG => 'jpg', IMAGETYPE_WEBP => 'webp', IMAGETYPE_GIF => 'gif'];
    if (!$info || !isset($map[$info[2]])) {
        $errors[] = 'Formato inválido. Use PNG, JPG, WEBP ou GIF.';
        return null;
    }
    // recusa arquivos com código embutido (polyglot)
    $head = (string)file_get_contents($f['tmp_name'], false, null, 0, 4096);
    if (stripos($head, '<?php') !== false || stripos($head, '<script') !== false) {
        $errors[] = 'Arquivo recusado por segurança.';
        return null;
    }
    if (!is_dir(UPLOAD_DIR)) mkdir(UPLOAD_DIR, 0775, true);
    $name = $field . '_' . date('Ymd_His') . '_' . bin2hex(random_bytes(4)) . '.' . $map[$info[2]];
    if (!move_uploaded_file($f['tmp_name'], UPLOAD_DIR . '/' . $name)) {
        $errors[] = 'Não foi possível salvar o arquivo. Verifique permissões da pasta uploads/.';
        return null;
    }
    @chmod(UPLOAD_DIR . '/' . $name, 0644);
    return 'uploads/' . $name;
}

function delete_upload(string $rel): void
{
    if (strpos($rel, 'uploads/') === 0) {
        $p = UPLOAD_DIR . '/' . basename($rel);
        if (is_file($p)) @unlink($p);
    }
}

/* ------------------------------------------------------- rede externa */

/** Bloqueia URLs para rede interna (evita SSRF pelo teste de servidor). */
function url_is_public(string $url): bool
{
    $p = parse_url($url);
    if (!$p || empty($p['host']) || !in_array(strtolower($p['scheme'] ?? ''), ['http', 'https'], true)) return false;
    $host = trim($p['host'], '[]');
    $ips = filter_var($host, FILTER_VALIDATE_IP) ? [$host] : (gethostbynamel($host) ?: []);
    if (!$ips) return false;
    foreach ($ips as $ip) {
        if (!filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE)) return false;
    }
    return true;
}

function http_get(string $url, string $ua = '', int $timeout = 12): array
{
    if (!url_is_public($url)) {
        return ['code' => 0, 'body' => '', 'ms' => 0, 'error' => 'endereço não permitido (rede interna)'];
    }
    if (!function_exists('curl_init')) {
        $ctx = stream_context_create(['http' => ['timeout' => $timeout, 'header' => 'User-Agent: ' . ($ua ?: 'NovaTV-Panel') . "\r\n"]]);
        $t = microtime(true);
        $body = @file_get_contents($url, false, $ctx);
        return ['code' => $body === false ? 0 : 200, 'body' => (string)$body, 'ms' => (int)((microtime(true) - $t) * 1000), 'error' => $body === false ? 'falha de conexão' : ''];
    }
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_MAXREDIRS => 3,
        CURLOPT_PROTOCOLS => CURLPROTO_HTTP | CURLPROTO_HTTPS,
        CURLOPT_REDIR_PROTOCOLS => CURLPROTO_HTTP | CURLPROTO_HTTPS,
        CURLOPT_CONNECTTIMEOUT => 8,
        CURLOPT_TIMEOUT => $timeout,
        CURLOPT_USERAGENT => $ua ?: 'Mozilla/5.0 (Linux; Android 10) NovaTV-Panel',
        CURLOPT_SSL_VERIFYPEER => false,
        CURLOPT_SSL_VERIFYHOST => 0,
        CURLOPT_ENCODING => '',
        CURLOPT_MAXFILESIZE => 20 * 1024 * 1024,
    ]);
    $t = microtime(true);
    $body = curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $err = curl_error($ch);
    curl_close($ch);
    return ['code' => $code, 'body' => is_string($body) ? $body : '', 'ms' => (int)((microtime(true) - $t) * 1000), 'error' => $err];
}

/** Limite de requisições por IP por minuto na API pública. */
function api_rate_limited(int $perMinute): bool
{
    if ($perMinute <= 0) return false;
    $pdo = db();
    $minute = intdiv(time(), 60);
    $ip = client_ip();
    $pdo->prepare('INSERT INTO api_hits (ip, minute, hits) VALUES (?, ?, 1) ON CONFLICT(ip, minute) DO UPDATE SET hits = hits + 1')
        ->execute([$ip, $minute]);
    if (random_int(1, 50) === 1) $pdo->prepare('DELETE FROM api_hits WHERE minute < ?')->execute([$minute - 5]);
    $st = $pdo->prepare('SELECT hits FROM api_hits WHERE ip=? AND minute=?');
    $st->execute([$ip, $minute]);
    return (int)$st->fetchColumn() > $perMinute;
}
