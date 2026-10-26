<?php
require __DIR__ . '/inc/bootstrap.php';
require_login();
if (is_admin()) redirect('settings.php');

$rid = reseller_id();
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $r = reseller($rid);
    $cur = (string)($_POST['current'] ?? '');
    $new = (string)($_POST['new'] ?? '');
    if (!$r || !password_verify($cur, $r['password_hash'])) flash('Senha atual incorreta.', 'err');
    elseif ($new !== ($_POST['new2'] ?? '')) flash('As senhas não conferem.', 'err');
    elseif ($m = password_ok($new)) flash($m, 'err');
    else {
        db()->prepare('UPDATE resellers SET password_hash=? WHERE id=?')->execute([password_hash($new, PASSWORD_DEFAULT), $rid]);
        audit('senha_alterada');
        flash('Senha alterada.');
    }
    redirect('account.php');
}
$me = reseller($rid);
$pageTitle = 'Minha conta';
$active = 'account';
require __DIR__ . '/inc/header.php';
?>
<div class="grid g2">
  <div class="card">
    <h2>Dados da revenda</h2>
    <table>
      <tr><th>Nome</th><td><?= e($me['name']) ?></td></tr>
      <tr><th>Usuário</th><td><?= e($me['username']) ?></td></tr>
      <tr><th>Código</th><td class="mono"><?= e($me['code']) ?></td></tr>
      <tr><th>Aparelhos</th><td><?= reseller_device_count($rid) ?> / <?= (int)$me['max_devices'] ?></td></tr>
      <tr><th>Validade</th><td><?= $me['expires_at'] ? e(date('d/m/Y', strtotime($me['expires_at']))) : 'Sem prazo' ?></td></tr>
    </table>
    <p class="hint" style="margin-top:10px">Para aumentar o limite, fale com o administrador.</p>
  </div>
  <form method="post" class="card" autocomplete="off"><?= csrf_field() ?>
    <h2>Trocar senha</h2>
    <div class="field"><label>Senha atual</label><input type="password" name="current" required autocomplete="current-password"></div>
    <div class="field"><label>Nova senha (8+, letras e números)</label><input type="password" name="new" required autocomplete="new-password"></div>
    <div class="field"><label>Repita a nova senha</label><input type="password" name="new2" required autocomplete="new-password"></div>
    <button class="btn">Alterar senha</button>
  </form>
</div>
<?php require __DIR__ . '/inc/footer.php';
