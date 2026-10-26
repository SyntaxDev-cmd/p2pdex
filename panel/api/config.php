<?php
/**
 * API pública consumida pelo app.
 * GET/POST /api/config.php?key=API_KEY&device_id=..&model=..&platform=tv|mobile&android=..&version=..&username=..&server_id=..
 * Responde com tema, abas, servidores, kids, avisos, banners, manutenção e atualização.
 * Suporta ETag (If-None-Match) para o app não baixar nada quando não mudou.
 */
define('NO_SESSION', true);
require dirname(__DIR__) . '/inc/bootstrap.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-cache');
header('Access-Control-Allow-Origin: *');
header('X-Content-Type-Options: nosniff');

function out(array $data, int $code = 200): void
{
    http_response_code($code);
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function req(string $k, int $max = 120): string
{
    $v = $_POST[$k] ?? $_GET[$k] ?? '';
    return is_string($v) ? mb_substr(trim($v), 0, $max) : '';
}

try {
    $s = settings();
    if (api_rate_limited((int)$s['api_rate_limit'])) {
        out(['ok' => false, 'error' => 'rate_limited'], 429);
    }
    $key = req('key', 64);
    if ($s['api_key_required'] === '1' && ($key === '' || !hash_equals($s['api_key'], $key))) {
        out(['ok' => false, 'error' => 'invalid_key'], 401);
    }

    $pdo = db();

    // ---- revenda (código no APK ou digitado no login)
    $code = strtoupper(preg_replace('/[^A-Za-z0-9]/', '', req('reseller', 12)));
    $reseller = null;
    if ($code !== '') {
        $reseller = reseller_by_code($code);
        if (!$reseller || !$reseller['active'] || reseller_expired($reseller)) {
            out(['ok' => false, 'error' => 'invalid_reseller', 'message' => 'Código de revenda inválido, inativo ou vencido. Fale com quem te vendeu o acesso.'], 403);
        }
    } elseif ($s['require_reseller_code'] === '1') {
        out(['ok' => false, 'error' => 'reseller_required', 'message' => 'Informe o código da sua revenda.'], 403);
    }
    $rid = $reseller ? (int)$reseller['id'] : 0;

    // ---- registro do aparelho e limite de vagas da revenda
    $blocked = false;
    $blockReason = '';
    $deviceId = preg_replace('/[^a-zA-Z0-9_-]/', '', req('device_id', 64));
    if ($deviceId !== '' && ($s['register_devices'] === '1' || $rid > 0)) {
        $st = $pdo->prepare('SELECT id, blocked, reseller_id FROM devices WHERE device_id=?');
        $st->execute([$deviceId]);
        $existing = $st->fetch();
        $isNewForReseller = !$existing || (int)$existing['reseller_id'] !== $rid;
        if ($rid > 0 && $isNewForReseller && reseller_device_count($rid) >= (int)$reseller['max_devices']) {
            $blocked = true;
            $blockReason = 'limit';
        } else {
            $platform = req('platform', 10) === 'tv' ? 'tv' : 'mobile';
            $pdo->prepare("INSERT INTO devices (device_id, model, platform, android, app_version, username, server_id, ip, reseller_id)
                VALUES (:d, :m, :p, :a, :v, :u, :s, :ip, :r)
                ON CONFLICT(device_id) DO UPDATE SET model=excluded.model, platform=excluded.platform, android=excluded.android,
                  app_version=excluded.app_version, username=CASE WHEN excluded.username<>'' THEN excluded.username ELSE devices.username END,
                  server_id=CASE WHEN excluded.server_id>0 THEN excluded.server_id ELSE devices.server_id END,
                  ip=excluded.ip, reseller_id=excluded.reseller_id, last_seen=datetime('now','localtime')")
                ->execute([
                    ':d' => $deviceId, ':m' => req('model', 80), ':p' => $platform, ':a' => req('android', 10),
                    ':v' => req('version', 20), ':u' => req('username', 60), ':s' => (int)req('server_id', 10), ':ip' => client_ip(), ':r' => $rid,
                ]);
            if ($existing && (int)$existing['blocked'] === 1) {
                $blocked = true;
                $blockReason = 'blocked';
            }
        }
    }

    $st = $pdo->prepare('SELECT * FROM servers WHERE active=1 AND reseller_id=? ORDER BY priority DESC, id ASC');
    $st->execute([$rid]);
    $serverRows = $st->fetchAll();
    $servers = [];
    foreach ($serverRows as $r) {
        $servers[] = [
            'id' => (int)$r['id'],
            'name' => $r['name'],
            'type' => $r['type'],
            'urls' => lines($r['urls']),
            'm3u_url' => $r['m3u_url'],
            'epg_url' => $r['epg_url'],
            'user_agent' => $r['user_agent'] ?: $s['default_user_agent'],
            'output' => $r['output'],
            'user_suffix' => str_starts_with($r['p2p_email'], '@') ? $r['p2p_email'] : '',
            'stream_base' => $r['p2p_lib'],
            'stream_template' => $r['p2p_template'],
        ];
    }

    // aparelho bloqueado ou sem vaga não recebe os endereços dos servidores
    if ($blocked) $servers = [];

    $tabs = [];
    foreach (['highlights', 'tv', 'sports', 'movies', 'series', 'kids', 'anime', 'favorites'] as $t) {
        if ($s['tab_' . $t] === '1') $tabs[] = ['id' => $t, 'label' => $s['label_' . $t], 'icon' => asset_url($s['icon_' . $t] ?? ''), 'icon_only' => ($s['tab_icon_only'] ?? '1') === '1'];
    }

    $banners = [];
    foreach ($pdo->query('SELECT * FROM banners WHERE active=1 ORDER BY position ASC, id DESC LIMIT 12') as $b) {
        $banners[] = ['id' => (int)$b['id'], 'title' => $b['title'], 'subtitle' => $b['subtitle'], 'image' => asset_url($b['image']),
            'action' => $b['action_type'], 'value' => $b['action_value']];
    }

    $messages = [];
    foreach ($pdo->query('SELECT id, title, body, popup, created_at FROM messages WHERE active=1 ORDER BY id DESC LIMIT 20') as $m) {
        $messages[] = ['id' => (int)$m['id'], 'title' => $m['title'], 'body' => $m['body'], 'popup' => (bool)$m['popup'], 'date' => $m['created_at']];
    }

    $payload = [
        'ok' => true,
        'app' => [
            'name' => $s['app_name'],
            'logo' => asset_url($s['logo']),
            'background' => asset_url($s['background_image']),
            'background_mode' => $s['background_mode'],
            'splash_text' => $s['splash_text'],
            'welcome' => $s['welcome_message'],
            'support' => $reseller && $reseller['whatsapp'] !== '' ? 'Suporte: ' . $reseller['whatsapp'] : $s['support_text'],
            'reseller' => $reseller ? $reseller['name'] : '',
            'ask_reseller_code' => $s['ask_reseller_code'] === '1' || $s['require_reseller_code'] === '1',
            'start_tab' => $s['start_tab'],
            'allow_server_select' => $s['allow_server_select'] === '1',
            'home_mosaic' => ($s['home_mosaic'] ?? '1') === '1',
        ],
        'theme' => [
            'primary' => $s['color_primary'],
            'accent' => $s['color_accent'],
            'focus_text' => $s['color_focus_text'],
            'bg1' => $s['color_bg1'],
            'bg2' => $s['color_bg2'],
            'surface' => $s['color_surface'],
            'surface_opacity' => (int)$s['surface_opacity'],
            'text' => $s['color_text'],
            'text2' => $s['color_text2'],
            'kids' => $s['color_kids'],
            'radius' => (int)$s['corner_radius'],
            'scale' => (int)$s['ui_scale'],
        ],
        'tabs' => $tabs,
        'servers' => $servers,
        'filters' => [
            'kids' => keywords($s['kids_keywords']),
            'anime' => keywords($s['anime_keywords']),
            'sports' => keywords($s['sports_keywords']),
            'adult' => keywords($s['adult_keywords']),
            'hide_adult' => $s['hide_adult'] === '1',
            'kids_lock' => $s['kids_lock'] === '1',
            'pin' => $s['parental_pin'],
        ],
        'games' => [
            'enabled' => $s['games_enabled'] === '1' && trim($s['games_api_url']) !== '',
            'url' => base_url() . '/api/games.php',
        ],
        'tmdb' => [
            'enabled' => $s['tmdb_enabled'] === '1',
            'api_key' => $s['tmdb_api_key'],
            'token' => $s['tmdb_token'],
            'language' => $s['tmdb_language'] ?: 'pt-BR',
        ],
        'player' => [
            'buffer' => $s['player_buffer'],
            'epg' => $s['epg_enabled'] === '1',
        ],
        'banners' => $banners,
        'messages' => $messages,
        'maintenance' => ['on' => $s['maintenance'] === '1', 'message' => $s['maintenance_message']],
        'update' => ['version_code' => (int)$s['latest_version_code'], 'url' => $s['update_url'], 'force' => $s['force_update'] === '1'],
        'device' => [
            'blocked' => $blocked,
            'reason' => $blockReason,
            'message' => $blockReason === 'limit'
                ? 'Limite de aparelhos da revenda atingido. Peça para liberar uma vaga.'
                : ($blocked ? 'Este aparelho foi bloqueado.' : ''),
        ],
    ];

    $etag = '"' . substr(sha1(json_encode($payload)), 0, 20) . '"';
    header('ETag: ' . $etag);
    if (trim((string)($_SERVER['HTTP_IF_NONE_MATCH'] ?? '')) === $etag) {
        http_response_code(304);
        exit;
    }
    out($payload);
} catch (Throwable $ex) {
    error_log('[api/config] ' . $ex->getMessage());
    out(['ok' => false, 'error' => 'server_error'], 500);
}
