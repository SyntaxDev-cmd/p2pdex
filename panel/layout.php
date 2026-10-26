<?php
require __DIR__ . '/inc/bootstrap.php';
require_admin();

$tabs = ['highlights' => 'Início (menu inicial em mosaico)', 'tv' => 'TV ao vivo', 'sports' => 'Jogos do dia', 'movies' => 'Filmes', 'series' => 'Séries', 'kids' => 'Kids', 'anime' => 'Anime', 'favorites' => 'Favoritos'];

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $save = [];
    $errors = [];
    foreach ($tabs as $k => $_) {
        $save['tab_' . $k] = !empty($_POST['tab_' . $k]) ? '1' : '0';
        $save['label_' . $k] = post_str('label_' . $k, 20) ?: mb_strtoupper($tabs[$k]);
        if (!empty($_POST['remove_icon_' . $k])) {
            delete_upload(setting('icon_' . $k));
            $save['icon_' . $k] = '';
        }
        $up = handle_upload('icon_' . $k, $errors);
        if ($up) {
            delete_upload(setting('icon_' . $k));
            $save['icon_' . $k] = $up;
        }
    }
    $start = (string)($_POST['start_tab'] ?? 'tv');
    $save['start_tab'] = isset($tabs[$start]) ? $start : 'tv';
    if ($save['tab_' . $save['start_tab']] !== '1') $save['tab_' . $save['start_tab']] = '1';
    $save['home_mosaic'] = !empty($_POST['home_mosaic']) ? '1' : '0';
    save_settings($save);
    audit('abas_salvas');
    foreach ($errors as $er) flash($er, 'err');
    flash('Abas atualizadas.');
    redirect('layout.php');
}
$s = settings();
$pageTitle = 'Abas e ícones';
$active = 'layout';
require __DIR__ . '/inc/header.php';
?>
<form method="post" enctype="multipart/form-data">
<?= csrf_field() ?>
<div class="card">
  <h2>Abas do menu superior</h2>
  <p class="hint" style="margin-bottom:14px">Ative/desative, renomeie e coloque um ícone em cada aba. A ordem segue a da lista.</p>
  <div class="tabs-editor">
    <?php foreach ($tabs as $k => $label): $icon = $s['icon_' . $k] ?? ''; ?>
    <div class="tab-row">
      <div class="tab-preview">
        <?php if ($icon): ?><img src="<?= e(asset_url($icon)) ?>" alt=""><?php else: ?><span class="noicon">sem ícone</span><?php endif; ?>
        <b><?= e($s['label_' . $k]) ?></b>
      </div>
      <div class="field"><label><?= e($label) ?></label><input type="text" name="label_<?= $k ?>" value="<?= e($s['label_' . $k]) ?>" maxlength="20"></div>
      <div class="field"><label>Ícone (opcional)</label><input type="file" name="icon_<?= $k ?>" accept="image/png,image/webp">
        <?php if ($icon): ?><label class="switch" style="margin-top:6px"><input type="checkbox" name="remove_icon_<?= $k ?>" value="1"> remover ícone</label><?php endif; ?></div>
      <div class="tab-flags">
        <label class="switch"><input type="checkbox" name="tab_<?= $k ?>" value="1" <?= $s['tab_' . $k] === '1' ? 'checked' : '' ?>> Ativa</label>
        <label class="switch"><input type="radio" name="start_tab" value="<?= $k ?>" <?= $s['start_tab'] === $k ? 'checked' : '' ?>> Abrir aqui</label>
      </div>
    </div>
    <?php endforeach; ?>
  </div>
  <div class="alert warn" style="margin-top:14px">
    <b>Tamanho ideal dos ícones:</b> PNG com fundo transparente, <b>128 × 128 px</b> (mínimo 96 × 96), desenho centralizado com ~10% de margem.
    Prefira ícones brancos ou de uma cor só — o app mostra com 24dp ao lado do nome da aba e destaca quando a aba recebe o foco.
  </div>
</div>
<div class="card">
  <h2>Tela inicial</h2>
  <label class="switch"><input type="checkbox" name="home_mosaic" value="1" <?= $s['home_mosaic'] === '1' ? 'checked' : '' ?>> Mosaico de destaques com atalhos (Gênero, Ano, No cinema, Todos) em Destaques, Filmes e Séries</label>
  <p class="hint" style="margin-top:8px">As abas JOGOS e ANIME só aparecem quando há conteúdo (jogos do dia ou categorias com as palavras-chave).</p>
</div>
<button class="btn">💾 Salvar</button>
</form>
<?php require __DIR__ . '/inc/footer.php';
