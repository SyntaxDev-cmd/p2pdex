<?php
/** Recebe relatórios de fechamento inesperado do app (POST). */
define('NO_SESSION', true);
require dirname(__DIR__) . '/inc/bootstrap.php';

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');

function crash_out(array $d, int $code = 200): void
{
    http_response_code($code);
    echo json_encode($d);
    exit;
}

try {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') crash_out(['ok' => false], 405);
    if (api_rate_limited(20)) crash_out(['ok' => false, 'error' => 'rate_limited'], 429);
    $s = settings();
    $key = (string)($_POST['key'] ?? '');
    if ($s['api_key_required'] === '1' && !hash_equals($s['api_key'], $key)) crash_out(['ok' => false], 401);

    $trace = mb_substr((string)($_POST['trace'] ?? ''), 0, 16000);
    if (trim($trace) === '') crash_out(['ok' => false], 400);
    // resumo: primeira linha de exceção + primeira linha do app
    $summary = '';
    foreach (preg_split('/\r?\n/', $trace) as $line) {
        if ($summary === '' && preg_match('/(Exception|Error)/', $line)) $summary = trim($line);
        if (strpos($line, 'com.novatv') !== false) { $summary .= ' @ ' . trim($line); break; }
    }
    $pdo = db();
    $pdo->prepare('INSERT INTO app_errors (device_id, model, android, app_version, summary, trace, ip) VALUES (?, ?, ?, ?, ?, ?, ?)')->execute([
        preg_replace('/[^a-zA-Z0-9_-]/', '', mb_substr((string)($_POST['device_id'] ?? ''), 0, 64)),
        mb_substr((string)($_POST['model'] ?? ''), 0, 80),
        mb_substr((string)($_POST['android'] ?? ''), 0, 10),
        mb_substr((string)($_POST['version'] ?? ''), 0, 30),
        mb_substr($summary, 0, 400),
        $trace,
        client_ip(),
    ]);
    // guarda no máximo 500 relatórios
    $pdo->exec('DELETE FROM app_errors WHERE id NOT IN (SELECT id FROM app_errors ORDER BY id DESC LIMIT 500)');
    crash_out(['ok' => true]);
} catch (Throwable $e) {
    error_log('[api/crash] ' . $e->getMessage());
    crash_out(['ok' => false], 500);
}
