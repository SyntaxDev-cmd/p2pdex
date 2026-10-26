<?php
require __DIR__ . '/inc/bootstrap.php';

if (admin_count() === 0) redirect('setup.php');
if (!empty($_SESSION['admin_id'])) redirect('index.php');

$error = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    csrf_check();
    $ip = client_ip();
    if (too_many_attempts($ip)) {
        $error = 'Muitas tentativas. Aguarde 15 minutos.';
    } else {
        $st = db()->prepare('SELECT * FROM admins WHERE username=?');
        $st->execute([trim((string)($_POST['username'] ?? ''))]);
        $a = $st->fetch();
        if ($a && password_verify((string)($_POST['password'] ?? ''), $a['password_hash'])) {
            session_regenerate_id(true);
            $_SESSION['admin_id'] = (int)$a['id'];
            $_SESSION['admin_user'] = $a['username'];
            $_SESSION['login_at'] = time();
            redirect('index.php');
        }
        register_attempt($ip);
        $error = 'Usuário ou senha inválidos.';
    }
}
?><!doctype html>
<html lang="pt-br"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Entrar · <?= e(setting('app_name')) ?></title>
<link rel="stylesheet" href="assets/panel.css?v=<?= PANEL_VERSION ?>">
<style>:root{--brand:<?= e(setting('color_primary')) ?>}</style></head>
<body><div class="auth"><form class="card" method="post">
  <?php if (setting('logo')): ?><img src="<?= e(asset_url(setting('logo'))) ?>" alt="" style="height:56px;margin-bottom:12px"><?php endif; ?>
  <h1><?= e(setting('app_name')) ?></h1><p class="hint" style="margin-bottom:20px">Acesse o painel de controle</p>
  <?php if ($error): ?><div class="alert err"><?= e($error) ?></div><?php endif; ?>
  <?= csrf_field() ?>
  <div class="field"><label>Usuário</label><input type="text" name="username" autofocus required autocomplete="username"></div>
  <div class="field"><label>Senha</label><input type="password" name="password" required autocomplete="current-password"></div>
  <button class="btn" style="width:100%;justify-content:center">Entrar</button>
</form></div></body></html>
