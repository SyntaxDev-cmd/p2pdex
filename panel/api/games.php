<?php
/** Jogos do dia normalizados para o app. GET /api/games.php */
define('NO_SESSION', true);
require dirname(__DIR__) . '/inc/bootstrap.php';
require dirname(__DIR__) . '/inc/games.php';

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('Cache-Control: public, max-age=120');

try {
    if (api_rate_limited((int)setting('api_rate_limit', '120'))) {
        http_response_code(429);
        echo json_encode(['ok' => false, 'error' => 'rate_limited']);
        exit;
    }
    $r = games_get();
    echo json_encode([
        'ok' => $r['ok'],
        'updated' => $r['updated'],
        'games' => $r['games'],
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
} catch (Throwable $e) {
    error_log('[api/games] ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['ok' => false, 'error' => 'server_error']);
}
