<?php
require __DIR__ . '/inc/bootstrap.php';
require_login();

$tabs = ['tv' => 'TV ao vivo', 'sports' => 'Jogos (esportes ao vivo)', 'highlights' => 'Destaques', 'movies' => 'Filmes', 'series' => 'Séries', 'kids' => 'Kids', 'anime' => 'Anime', 'favorites' => 'Favoritos (canais, filmes e séries)'];

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $save = [];
    foreach ($tabs as $k => $_) {
        $save['tab_' . $k] = !empty($_POST['tab_' . $k]) ? '1' : '0';
        $save['label_' . $k] = mb_substr(trim((string)($_POST['label_' . $k] ?? '')), 0, 20) ?: mb_strtoupper($tabs[$k]);
    }
    $start = (string)($_POST['start_tab'] ?? 'tv');
    $save['start_tab'] = isset($tabs[$start]) ? $start : 'tv';
    if ($save['tab_' . $save['start_tab']] !== '1') $save['tab_' . $save['start_tab']] = '1';
    save_settings($save);
    flash('Abas atualizadas.');
    redirect('layout.php');
}
$s = settings();
$pageTitle = 'Abas e menus';
$active = 'layout';
require __DIR__ . '/inc/header.php';
?>
<form method="post" class="card">
  <?= csrf_field() ?>
  <h2>Abas do menu superior</h2>
  <p class="hint" style="margin-bottom:14px">Ative/desative e renomeie cada aba. A ordem segue a da lista.</p>
  <div class="tbl"><table>
    <tr><th>Aba</th><th>Ativa</th><th>Nome exibido</th><th>Abrir nesta aba</th></tr>
    <?php foreach ($tabs as $k => $label): ?>
    <tr>
      <td><?= e($label) ?></td>
      <td><label class="switch"><input type="checkbox" name="tab_<?= $k ?>" value="1" <?= $s['tab_' . $k] === '1' ? 'checked' : '' ?>></label></td>
      <td><input type="text" name="label_<?= $k ?>" value="<?= e($s['label_' . $k]) ?>" maxlength="20"></td>
      <td><input type="radio" name="start_tab" value="<?= $k ?>" <?= $s['start_tab'] === $k ? 'checked' : '' ?>></td>
    </tr>
    <?php endforeach; ?>
  </table></div>
  <p class="hint">As abas JOGOS e ANIME só aparecem se existir categoria com as palavras-chave correspondentes (configure em Kids e controle parental).</p>
  <button class="btn" style="margin-top:14px">💾 Salvar</button>
</form>
<?php require __DIR__ . '/inc/footer.php';
