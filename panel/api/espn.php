<?php
/**
 * Intermediário da API pública da ESPN para o NovaSports (quando o aparelho não alcança a ESPN).
 * GET /api/espn.php?u=https://site.api.espn.com/apis/...
 * Só aceita site.api.espn.com/apis/*. Guarda em cache: placar 30 s, notícias/tabela 10 min.
 */
define('NO_SESSION', true);
require dirname(__DIR__) . '/inc/bootstrap.php';

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');

function fail(int $code, string $msg): void
{
    http_response_code($code);
    echo json_encode(['ok' => false, 'error' => $msg]);
    exit;
}

try {
    if (api_rate_limited((int)setting('api_rate_limit', '120'))) fail(429, 'rate_limited');
    $u = (string)($_GET['u'] ?? '');
    $p = parse_url($u);
    if (!$p || ($p['scheme'] ?? '') !== 'https' || ($p['host'] ?? '') !== 'site.api.espn.com' || !str_starts_with($p['path'] ?? '', '/apis/')) {
        fail(400, 'url_not_allowed');
    }
    $ttl = str_contains($p['path'], 'scoreboard') ? 30 : 600;
    $dir = DATA_DIR . '/espn_cache';
    if (!is_dir($dir)) @mkdir($dir, 0775, true);
    $file = $dir . '/' . sha1($u) . '.json';
    if (is_file($file) && time() - filemtime($file) < $ttl) {
        header('X-Cache: hit');
        readfile($file);
        exit;
    }
    $r = http_get($u, 'Mozilla/5.0 NovaSports-Panel', 15);
    if ($r['code'] !== 200 || json_decode($r['body']) === null) {
        // ESPN fora: devolve o último resultado guardado
        if (is_file($file)) {
            header('X-Cache: stale');
            readfile($file);
            exit;
        }
        fail(502, 'espn_unavailable');
    }
    @file_put_contents($file, $r['body'], LOCK_EX);
    header('Cache-Control: public, max-age=' . $ttl);
    echo $r['body'];
} catch (Throwable $e) {
    error_log('[api/espn] ' . $e->getMessage());
    fail(500, 'server_error');
}
