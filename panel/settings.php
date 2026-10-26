<?php
require __DIR__ . '/inc/bootstrap.php';
require_admin();

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $op = $_POST['op'] ?? 'save';
    if ($op === 'save') {
        save_settings([
            'maintenance' => !empty($_POST['maintenance']) ? '1' : '0',
            'maintenance_message' => mb_substr(trim((string)($_POST['maintenance_message'] ?? '')), 0, 300),
            'welcome_message' => mb_substr(trim((string)($_POST['welcome_message'] ?? '')), 0, 300),
            'support_text' => mb_substr(trim((string)($_POST['support_text'] ?? '')), 0, 200),
            'latest_version_code' => (string)max(1, (int)($_POST['latest_version_code'] ?? 1)),
            'update_url' => trim((string)($_POST['update_url'] ?? '')),
            'force_update' => !empty($_POST['force_update']) ? '1' : '0',
            'allow_server_select' => !empty($_POST['allow_server_select']) ? '1' : '0',
            'register_devices' => !empty($_POST['register_devices']) ? '1' : '0',
            'epg_enabled' => !empty($_POST['epg_enabled']) ? '1' : '0',
            'player_buffer' => in_array($_POST['player_buffer'] ?? '', ['low', 'normal', 'high'], true) ? $_POST['player_buffer'] : 'normal',
            'default_user_agent' => mb_substr(trim((string)($_POST['default_user_agent'] ?? '')), 0, 200),
        ]);
        flash('Configurações salvas.');
    } elseif ($op === 'password') {
        $cur = (string)($_POST['current'] ?? '');
        $new = (string)($_POST['new'] ?? '');
        $st = db()->prepare('SELECT password_hash FROM admins WHERE id=?');
        $st->execute([(int)$_SESSION['uid']]);
        if (!password_verify($cur, (string)$st->fetchColumn())) flash('Senha atual incorreta.', 'err');
        elseif ($msg = password_ok($new)) flash($msg, 'err');
        else {
            db()->prepare('UPDATE admins SET password_hash=? WHERE id=?')->execute([password_hash($new, PASSWORD_DEFAULT), (int)$_SESSION['uid']]);
            audit('senha_alterada');
            flash('Senha alterada.');
        }
    } elseif ($op === 'regen_key') {
        save_settings(['api_key' => bin2hex(random_bytes(16))]);
        flash('Nova chave gerada. Atualize PANEL_KEY e gere um novo APK — apps antigos deixarão de conectar.', 'warn');
    } elseif ($op === 'export') {
        $data = ['settings' => settings(), 'servers' => db()->query('SELECT * FROM servers')->fetchAll()];
        unset($data['settings']['api_key']);
        header('Content-Type: application/json');
        header('Content-Disposition: attachment; filename="painel-backup-' . date('Ymd') . '.json"');
        echo json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        exit;
    } elseif ($op === 'import') {
        $raw = isset($_FILES['backup']['tmp_name']) && is_uploaded_file($_FILES['backup']['tmp_name']) ? file_get_contents($_FILES['backup']['tmp_name']) : '';
        $data = json_decode((string)$raw, true);
        if (!is_array($data) || !isset($data['settings'])) {
            flash('Arquivo de backup inválido.', 'err');
        } else {
            $allowed = array_diff(array_keys(default_settings()), ['api_key']);
            save_settings(array_intersect_key($data['settings'], array_flip($allowed)));
            if (!empty($data['servers']) && is_array($data['servers'])) {
                $cols = ['name', 'type', 'urls', 'm3u_url', 'epg_url', 'user_agent', 'output', 'p2p_email', 'p2p_lib', 'p2p_template', 'priority', 'active', 'notes'];
                $ins = db()->prepare('INSERT INTO servers (' . implode(',', $cols) . ') VALUES (' . implode(',', array_fill(0, count($cols), '?')) . ')');
                foreach ($data['servers'] as $srv) {
                    $ins->execute(array_map(fn($c) => $srv[$c] ?? ($c === 'priority' || $c === 'active' ? 0 : ''), $cols));
                }
            }
            flash('Backup importado.');
        }
    }
    redirect('settings.php');
}
$s = settings();
$pageTitle = 'Sistema e app';
$active = 'settings';
require __DIR__ . '/inc/header.php';
?>
<form method="post" class="grid g2">
  <?= csrf_field() ?><input type="hidden" name="op" value="save">
  <div class="card">
    <h2>Mensagens</h2>
    <div class="field"><label>Boas-vindas (tela de login)</label><input type="text" name="welcome_message" value="<?= e($s['welcome_message']) ?>"></div>
    <div class="field"><label>Texto de suporte</label><input type="text" name="support_text" value="<?= e($s['support_text']) ?>"></div>
    <label class="switch" style="margin-bottom:10px"><input type="checkbox" name="maintenance" value="1" <?= $s['maintenance'] === '1' ? 'checked' : '' ?>> Modo manutenção</label>
    <div class="field"><label>Mensagem de manutenção</label><input type="text" name="maintenance_message" value="<?= e($s['maintenance_message']) ?>"></div>
  </div>
  <div class="card">
    <h2>Atualização do app</h2>
    <div class="field"><label>Última versão (versionCode)</label><input type="number" name="latest_version_code" value="<?= e($s['latest_version_code']) ?>" min="1">
      <p class="hint">Se for maior que a versão instalada, o app oferece a atualização.</p></div>
    <div class="field"><label>Link do APK novo</label><input type="text" name="update_url" value="<?= e($s['update_url']) ?>" placeholder="https://seusite.com/app.apk"></div>
    <label class="switch"><input type="checkbox" name="force_update" value="1" <?= $s['force_update'] === '1' ? 'checked' : '' ?>> Atualização obrigatória</label>
  </div>
  <div class="card">
    <h2>Conexão e player</h2>
    <label class="switch" style="margin-bottom:10px"><input type="checkbox" name="allow_server_select" value="1" <?= $s['allow_server_select'] === '1' ? 'checked' : '' ?>> Cliente escolhe o servidor no login (senão: automático)</label>
    <label class="switch" style="margin-bottom:10px"><input type="checkbox" name="register_devices" value="1" <?= $s['register_devices'] === '1' ? 'checked' : '' ?>> Registrar dispositivos</label>
    <label class="switch" style="margin-bottom:14px"><input type="checkbox" name="epg_enabled" value="1" <?= $s['epg_enabled'] === '1' ? 'checked' : '' ?>> Mostrar guia de programação (EPG)</label>
    <div class="field"><label>Buffer do player</label><select name="player_buffer">
      <?php foreach (['low' => 'Baixo (zapping rápido, internet boa)', 'normal' => 'Normal (recomendado)', 'high' => 'Alto (internet instável)'] as $k => $l): ?><option value="<?= $k ?>" <?= $s['player_buffer'] === $k ? 'selected' : '' ?>><?= $l ?></option><?php endforeach; ?>
    </select></div>
    <div class="field"><label>User-Agent padrão</label><input type="text" name="default_user_agent" value="<?= e($s['default_user_agent']) ?>" placeholder="Vazio = padrão do app"></div>
  </div>
  <div class="card"><h2>&nbsp;</h2><button class="btn">💾 Salvar configurações</button></div>
</form>

<div class="grid g2">
  <form method="post" class="card"><?= csrf_field() ?><input type="hidden" name="op" value="password">
    <h2>Senha do admin</h2>
    <div class="field"><label>Senha atual</label><input type="password" name="current" required></div>
    <div class="field"><label>Nova senha</label><input type="password" name="new" required minlength="8"></div>
    <button class="btn">Alterar senha</button></form>
  <div class="card">
    <h2>Chave da API e backup</h2>
    <div class="result mono copy" data-copy><?= e($s['api_key']) ?></div>
    <form method="post" style="margin:12px 0"><?= csrf_field() ?><input type="hidden" name="op" value="regen_key"><button class="btn small danger" data-confirm="Gerar nova chave? Apps com a chave antiga param de funcionar.">Gerar nova chave</button></form>
    <form method="post" style="margin-bottom:12px"><?= csrf_field() ?><input type="hidden" name="op" value="export"><button class="btn small ghost">⬇ Exportar backup</button></form>
    <form method="post" enctype="multipart/form-data" class="row"><?= csrf_field() ?><input type="hidden" name="op" value="import"><input type="file" name="backup" accept="application/json"><button class="btn small ghost">⬆ Importar</button></form>
  </div>
</div>
<?php require __DIR__ . '/inc/footer.php';
