<?php
require __DIR__ . '/inc/bootstrap.php';

if (admin_count() === 0) redirect('setup.php');
if (!empty($_SESSION['role'])) redirect('index.php');

$error = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    csrf_check();
    $ip = client_ip();
    $user = post_str('username', 64);
    $pass = (string)($_POST['password'] ?? '');
    // campo-armadilha: robôs preenchem, pessoas não veem
    $bot = post_str('website') !== '';
    if (too_many_attempts($ip, $user)) {
        $error = 'Muitas tentativas. Aguarde 15 minutos e tente de novo.';
    } elseif ($bot || $user === '' || $pass === '') {
        register_attempt($ip, $user);
        $error = 'Usuário ou senha inválidos.';
    } else {
        $ok = false;
        $st = db()->prepare('SELECT * FROM admins WHERE username=?');
        $st->execute([$user]);
        if (($a = $st->fetch()) && password_verify($pass, $a['password_hash'])) {
            if (password_needs_rehash($a['password_hash'], PASSWORD_DEFAULT)) {
                db()->prepare('UPDATE admins SET password_hash=? WHERE id=?')->execute([password_hash($pass, PASSWORD_DEFAULT), $a['id']]);
            }
            login_as('admin', (int)$a['id'], $a['username']);
            $ok = true;
        } else {
            $st = db()->prepare('SELECT * FROM resellers WHERE username=?');
            $st->execute([$user]);
            $r = $st->fetch();
            if ($r && password_verify($pass, $r['password_hash'])) {
                if (!$r['active'] || reseller_expired($r)) {
                    $error = 'Seu acesso de revenda está inativo ou vencido. Fale com o administrador.';
                } else {
                    login_as('reseller', (int)$r['id'], $r['username'], (int)$r['id']);
                    db()->prepare("UPDATE resellers SET last_login=datetime('now','localtime') WHERE id=?")->execute([$r['id']]);
                    $ok = true;
                }
            }
        }
        if ($ok) {
            clear_attempts($ip);
            audit('login');
            redirect('index.php');
        }
        if ($error === '') {
            register_attempt($ip, $user);
            usleep(random_int(200000, 600000)); // atrasa força bruta
            $error = 'Usuário ou senha inválidos.';
        }
    }
}
$flashes = take_flashes();
?><!doctype html>
<html lang="pt-br"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>Entrar · <?= e(setting('app_name')) ?></title>
<link rel="stylesheet" href="assets/panel.css?v=<?= PANEL_VERSION ?>">
<style>:root{--brand:<?= e(setting('color_primary')) ?>}</style></head>
<body><div class="auth"><form class="card" method="post" autocomplete="on">
  <?php if (setting('logo')): ?><img src="<?= e(asset_url(setting('logo'))) ?>" alt="" class="auth-logo"><?php endif; ?>
  <h1><?= e(setting('app_name')) ?></h1><p class="hint" style="margin-bottom:20px">Painel do administrador e de revendas</p>
  <?php foreach ($flashes as $f): ?><div class="alert <?= e($f['type']) ?>"><?= e($f['msg']) ?></div><?php endforeach; ?>
  <?php if ($error): ?><div class="alert err"><?= e($error) ?></div><?php endif; ?>
  <?= csrf_field() ?>
  <div class="hp" aria-hidden="true"><label>Website</label><input type="text" name="website" tabindex="-1" autocomplete="off"></div>
  <div class="field"><label>Usuário</label><input type="text" name="username" autofocus required autocomplete="username" maxlength="64"></div>
  <div class="field"><label>Senha</label><input type="password" name="password" required autocomplete="current-password"></div>
  <button class="btn" style="width:100%;justify-content:center">Entrar</button>
  <p class="hint" style="margin-top:14px;text-align:center">🔒 Conexão protegida · bloqueio automático após tentativas erradas</p>
</form></div></body></html>
