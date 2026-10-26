<?php
declare(strict_types=1);

define('APP_ROOT', dirname(__DIR__));
define('DATA_DIR', APP_ROOT . '/data');
define('UPLOAD_DIR', APP_ROOT . '/uploads');
define('PANEL_VERSION', '1.0.0');

ini_set('display_errors', '0');
error_reporting(E_ALL);
date_default_timezone_set('America/Sao_Paulo');

if (session_status() === PHP_SESSION_NONE && PHP_SAPI !== 'cli' && !defined('NO_SESSION')) {
    session_name('NTVPANEL');
    session_set_cookie_params([
        'lifetime' => 0,
        'path' => '/',
        'secure' => !empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off',
        'httponly' => true,
        'samesite' => 'Lax',
    ]);
    session_start();
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
    $pdo->exec('PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;');
    migrate($pdo);
    return $pdo;
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
            first_seen TEXT NOT NULL DEFAULT (datetime('now','localtime')),
            last_seen TEXT NOT NULL DEFAULT (datetime('now','localtime'))
        );
        CREATE TABLE IF NOT EXISTS login_attempts (
            ip TEXT NOT NULL,
            ts INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_attempts_ip ON login_attempts(ip, ts);
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
        'tab_tv' => '1', 'label_tv' => 'TV',
        'tab_sports' => '1', 'label_sports' => 'JOGOS',
        'tab_highlights' => '1', 'label_highlights' => 'DESTAQUES',
        'tab_movies' => '1', 'label_movies' => 'FILMES',
        'tab_series' => '1', 'label_series' => 'SÉRIES',
        'tab_kids' => '1', 'label_kids' => 'KIDS',
        'tab_anime' => '1', 'label_anime' => 'ANIME',
        'start_tab' => 'tv',
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
        // Sistema
        'maintenance' => '0',
        'maintenance_message' => 'Estamos em manutenção. Volte em alguns minutos.',
        'latest_version_code' => '1',
        'update_url' => '',
        'force_update' => '0',
        'allow_server_select' => '1',
        'register_devices' => '1',
        'api_key' => '',
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
    $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
        || (($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https');
    $host = $_SERVER['HTTP_HOST'] ?? 'localhost';
    $docRoot = realpath($_SERVER['DOCUMENT_ROOT'] ?? '') ?: '';
    $root = realpath(APP_ROOT) ?: APP_ROOT;
    $path = '';
    if ($docRoot !== '' && strpos($root, $docRoot) === 0) {
        $path = str_replace('\\', '/', substr($root, strlen($docRoot)));
    } else {
        // fallback: diretório do script atual sem /api
        $path = rtrim(str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'] ?? '/')), '/');
        $path = preg_replace('#/api$#', '', $path);
    }
    return ($https ? 'https' : 'http') . '://' . $host . rtrim((string)$path, '/');
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
    return $_SERVER['HTTP_CF_CONNECTING_IP'] ?? $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';
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

function require_login(): void
{
    if (admin_count() === 0) {
        redirect('setup.php');
    }
    if (empty($_SESSION['admin_id'])) {
        redirect('login.php');
    }
    // expira após 8h
    if (time() - (int)($_SESSION['login_at'] ?? 0) > 8 * 3600) {
        session_destroy();
        redirect('login.php');
    }
    csrf_check();
}

function too_many_attempts(string $ip): bool
{
    $pdo = db();
    $pdo->prepare('DELETE FROM login_attempts WHERE ts < ?')->execute([time() - 900]);
    $st = $pdo->prepare('SELECT COUNT(*) FROM login_attempts WHERE ip=?');
    $st->execute([$ip]);
    return (int)$st->fetchColumn() >= 8;
}

function register_attempt(string $ip): void
{
    db()->prepare('INSERT INTO login_attempts (ip, ts) VALUES (?, ?)')->execute([$ip, time()]);
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
    if (!is_dir(UPLOAD_DIR)) mkdir(UPLOAD_DIR, 0775, true);
    $name = $field . '_' . date('Ymd_His') . '_' . bin2hex(random_bytes(4)) . '.' . $map[$info[2]];
    if (!move_uploaded_file($f['tmp_name'], UPLOAD_DIR . '/' . $name)) {
        $errors[] = 'Não foi possível salvar o arquivo. Verifique permissões da pasta uploads/.';
        return null;
    }
    return 'uploads/' . $name;
}

function delete_upload(string $rel): void
{
    if (strpos($rel, 'uploads/') === 0) {
        $p = APP_ROOT . '/' . basename(dirname($rel)) . '/' . basename($rel);
        if (is_file($p)) @unlink($p);
    }
}

/* ------------------------------------------------------- server tests */

function http_get(string $url, string $ua = '', int $timeout = 12): array
{
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
        CURLOPT_MAXREDIRS => 4,
        CURLOPT_CONNECTTIMEOUT => 8,
        CURLOPT_TIMEOUT => $timeout,
        CURLOPT_USERAGENT => $ua ?: 'NovaTV-Panel',
        CURLOPT_SSL_VERIFYPEER => false,
        CURLOPT_SSL_VERIFYHOST => 0,
        CURLOPT_ENCODING => '',
    ]);
    $t = microtime(true);
    $body = curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $err = curl_error($ch);
    curl_close($ch);
    return ['code' => $code, 'body' => is_string($body) ? $body : '', 'ms' => (int)((microtime(true) - $t) * 1000), 'error' => $err];
}
