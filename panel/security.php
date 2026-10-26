<?php
require __DIR__ . '/inc/bootstrap.php';
require_admin();

$pdo = db();
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $op = $_POST['op'] ?? '';
    if ($op === 'save') {
        save_settings([
            'api_rate_limit' => (string)max(0, min(5000, (int)($_POST['api_rate_limit'] ?? 120))),
            'api_key_required' => !empty($_POST['api_key_required']) ? '1' : '0',
            'ask_reseller_code' => !empty($_POST['ask_reseller_code']) ? '1' : '0',
            'require_reseller_code' => !empty($_POST['require_reseller_code']) ? '1' : '0',
        ]);
        audit('seguranca_config');
        flash('Configurações de segurança salvas.');
    } elseif ($op === 'unblock') {
        $pdo->exec('DELETE FROM login_attempts');
        audit('desbloqueio_logins');
        flash('Bloqueios de login liberados.');
    } elseif ($op === 'clear_log') {
        $pdo->exec("DELETE FROM audit_log WHERE created_at < datetime('now','localtime','-30 days')");
        flash('Registros com mais de 30 dias apagados.');
    }
    redirect('security.php');
}

$s = settings();
$blocked = $pdo->query("SELECT ip, COUNT(*) n, MAX(ts) last FROM login_attempts WHERE ts > " . (time() - 900) . " GROUP BY ip HAVING n >= 3 ORDER BY n DESC LIMIT 20")->fetchAll();
$log = $pdo->query('SELECT * FROM audit_log ORDER BY id DESC LIMIT 150')->fetchAll();

// checagens rápidas do ambiente
$checks = [
    'HTTPS ativo no painel' => is_https(),
    'Pasta data/ protegida (.htaccess)' => is_file(DATA_DIR . '/.htaccess'),
    'Uploads sem execução de PHP' => is_file(UPLOAD_DIR . '/.htaccess'),
    'PHP 8.1 ou superior' => version_compare(PHP_VERSION, '8.1', '>='),
    'Exibição de erros desligada' => ini_get('display_errors') === '0' || ini_get('display_errors') === '',
];

$pageTitle = 'Segurança e registros';
$active = 'security';
require __DIR__ . '/inc/header.php';
?>
<div class="grid g2">
  <div class="card">
    <h2>🛡 Proteções ativas</h2>
    <ul class="checks">
      <li class="ok">Senhas com hash bcrypt e troca automática de algoritmo</li>
      <li class="ok">Bloqueio após 8 erros por IP / 15 por usuário (15–30 min) + atraso aleatório</li>
      <li class="ok">Sessão: cookie HttpOnly/SameSite=Strict, expira em 30 min parado, amarrada ao navegador</li>
      <li class="ok">Proteção CSRF em todos os formulários</li>
      <li class="ok">Cabeçalhos CSP, X-Frame-Options, nosniff, HSTS (com HTTPS)</li>
      <li class="ok">Uploads só de imagem real, nome aleatório e sem execução de código</li>
      <li class="ok">Testes de servidor não acessam a rede interna (anti-SSRF)</li>
      <li class="ok">Banco SQLite com nome aleatório e pasta bloqueada</li>
      <li class="ok">Limite de requisições por IP na API do app</li>
    </ul>
    <h2 style="margin-top:18px">Ambiente</h2>
    <table>
      <?php foreach ($checks as $label => $ok): ?>
      <tr><td><?= e($label) ?></td><td><span class="badge <?= $ok ? 'on' : 'off' ?>"><?= $ok ? 'OK' : 'Atenção' ?></span></td></tr>
      <?php endforeach; ?>
    </table>
    <?php if (!is_https()): ?><p class="hint" style="margin-top:8px">Ative o SSL grátis na Hostinger (hPanel → Segurança → SSL) e use https:// no app para proteger senhas e dados.</p><?php endif; ?>
  </div>

  <form method="post" class="card"><?= csrf_field() ?><input type="hidden" name="op" value="save">
    <h2>API do aplicativo</h2>
    <div class="field"><label>Limite de requisições por IP por minuto (0 = sem limite)</label><input type="number" name="api_rate_limit" min="0" max="5000" value="<?= e($s['api_rate_limit']) ?>"></div>
    <label class="switch" style="margin-bottom:10px"><input type="checkbox" name="api_key_required" value="1" <?= $s['api_key_required'] === '1' ? 'checked' : '' ?>> Exigir chave da API (panel_key no APK)</label>
    <label class="switch" style="margin-bottom:10px"><input type="checkbox" name="ask_reseller_code" value="1" <?= $s['ask_reseller_code'] === '1' ? 'checked' : '' ?>> Mostrar campo “Código da revenda” no login do app</label>
    <label class="switch" style="margin-bottom:14px"><input type="checkbox" name="require_reseller_code" value="1" <?= $s['require_reseller_code'] === '1' ? 'checked' : '' ?>> Exigir código de revenda (sem código o app não conecta)</label>
    <button class="btn">💾 Salvar</button>

    <h2 style="margin-top:22px">Tentativas de login recentes</h2>
    <?php if (!$blocked): ?><p class="hint">Nenhuma tentativa suspeita nos últimos 15 minutos.</p><?php else: ?>
      <table><tr><th>IP</th><th>Tentativas</th><th>Última</th></tr>
      <?php foreach ($blocked as $b): ?><tr><td class="mono"><?= e($b['ip']) ?></td><td><?= (int)$b['n'] ?><?= $b['n'] >= 8 ? ' <span class="badge off">bloqueado</span>' : '' ?></td><td><?= date('H:i:s', (int)$b['last']) ?></td></tr><?php endforeach; ?>
      </table>
    <?php endif; ?>
  </form>
</div>
<div class="row" style="margin-bottom:18px">
  <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="unblock"><button class="btn small ghost" data-confirm="Liberar todos os IPs bloqueados no login?">Liberar bloqueios de login</button></form>
</div>

<div class="card">
  <div class="row" style="justify-content:space-between"><h2 style="margin:0">Registro de atividades</h2>
    <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="clear_log"><button class="btn small ghost">Apagar com +30 dias</button></form></div>
  <?php if (!$log): ?><div class="empty">Sem registros.</div><?php else: ?>
  <div class="tbl"><table>
    <tr><th>Quando</th><th>Quem</th><th>Ação</th><th>Detalhes</th><th>IP</th></tr>
    <?php foreach ($log as $l): ?><tr><td><?= e($l['created_at']) ?></td><td><?= e($l['who']) ?></td><td><?= e($l['action']) ?></td><td><?= e($l['details']) ?></td><td class="mono"><?= e($l['ip']) ?></td></tr><?php endforeach; ?>
  </table></div>
  <?php endif; ?>
</div>
<?php require __DIR__ . '/inc/footer.php';
