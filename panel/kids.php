<?php
require __DIR__ . '/inc/bootstrap.php';
require_login();

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $pin = preg_replace('/\D/', '', (string)($_POST['parental_pin'] ?? ''));
    save_settings([
        'kids_keywords' => implode(', ', keywords((string)($_POST['kids_keywords'] ?? ''))),
        'sports_keywords' => implode(', ', keywords((string)($_POST['sports_keywords'] ?? ''))),
        'anime_keywords' => implode(', ', keywords((string)($_POST['anime_keywords'] ?? ''))),
        'adult_keywords' => implode(', ', keywords((string)($_POST['adult_keywords'] ?? ''))),
        'hide_adult' => !empty($_POST['hide_adult']) ? '1' : '0',
        'kids_lock' => !empty($_POST['kids_lock']) ? '1' : '0',
        'parental_pin' => strlen($pin) >= 4 ? substr($pin, 0, 8) : '0000',
    ]);
    flash('Configurações salvas.');
    redirect('kids.php');
}
$s = settings();
$pageTitle = 'Kids e controle parental';
$active = 'kids';
require __DIR__ . '/inc/header.php';
?>
<form method="post">
<?= csrf_field() ?>
<div class="grid g2">
  <div class="card">
    <h2>🧸 Seção KIDS</h2>
    <div class="field"><label>Palavras-chave das categorias infantis</label>
      <textarea name="kids_keywords"><?= e($s['kids_keywords']) ?></textarea>
      <p class="hint">Separe por vírgula. Toda categoria (canais, filmes ou séries) cujo nome contenha uma dessas palavras vai para a aba KIDS.</p></div>
    <label class="switch"><input type="checkbox" name="kids_lock" value="1" <?= $s['kids_lock'] === '1' ? 'checked' : '' ?>> Modo criança: pedir PIN para sair da aba KIDS</label>
  </div>
  <div class="card">
    <h2>⚽ JOGOS e 🍥 ANIME</h2>
    <div class="field"><label>Palavras-chave de esportes (aba JOGOS — canais ao vivo)</label>
      <textarea name="sports_keywords"><?= e($s['sports_keywords']) ?></textarea></div>
    <div class="field"><label>Palavras-chave de anime</label>
      <textarea name="anime_keywords"><?= e($s['anime_keywords']) ?></textarea></div>
  </div>
  <div class="card">
    <h2>🔒 Controle parental</h2>
    <label class="switch" style="margin-bottom:14px"><input type="checkbox" name="hide_adult" value="1" <?= $s['hide_adult'] === '1' ? 'checked' : '' ?>> Bloquear categorias adultas com PIN</label>
    <div class="field"><label>Palavras-chave adultas</label><textarea name="adult_keywords"><?= e($s['adult_keywords']) ?></textarea></div>
    <div class="field"><label>PIN padrão (4 a 8 números)</label><input type="text" inputmode="numeric" name="parental_pin" value="<?= e($s['parental_pin']) ?>" maxlength="8">
      <p class="hint">O cliente pode trocar o PIN no próprio aparelho (Configurações do app).</p></div>
  </div>
</div>
<button class="btn">💾 Salvar</button>
</form>
<?php require __DIR__ . '/inc/footer.php';
