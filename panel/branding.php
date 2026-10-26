<?php
require __DIR__ . '/inc/bootstrap.php';
require_login();

$colorKeys = [
    'color_primary' => ['Cor principal', 'Faixas, títulos de lista e botões'],
    'color_accent' => ['Cor do foco', 'Fundo do item selecionado no controle remoto'],
    'color_focus_text' => ['Texto em foco', 'Texto do item selecionado'],
    'color_bg1' => ['Fundo — cor 1', 'Início do degradê'],
    'color_bg2' => ['Fundo — cor 2', 'Fim do degradê'],
    'color_surface' => ['Painéis', 'Cor das caixas (lista de canais etc.)'],
    'color_text' => ['Texto', 'Texto principal'],
    'color_text2' => ['Texto secundário', 'Horários, descrições'],
    'color_kids' => ['Cor KIDS', 'Destaque da área infantil'],
];

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $errors = [];
    $save = [
        'app_name' => mb_substr(trim((string)($_POST['app_name'] ?? '')), 0, 40) ?: 'NovaTV',
        'splash_text' => mb_substr(trim((string)($_POST['splash_text'] ?? '')), 0, 120),
        'background_mode' => in_array($_POST['background_mode'] ?? '', ['gradient', 'image', 'solid'], true) ? $_POST['background_mode'] : 'gradient',
        'surface_opacity' => (string)max(0, min(100, (int)($_POST['surface_opacity'] ?? 35))),
        'corner_radius' => (string)max(0, min(32, (int)($_POST['corner_radius'] ?? 12))),
        'ui_scale' => (string)max(80, min(130, (int)($_POST['ui_scale'] ?? 100))),
    ];
    foreach (array_keys($colorKeys) as $k) {
        $v = strtoupper(trim((string)($_POST[$k] ?? '')));
        if (is_hex_color($v)) $save[$k] = $v; else $errors[] = 'Cor inválida em ' . $colorKeys[$k][0];
    }
    foreach (['logo', 'background_image'] as $f) {
        if (!empty($_POST['remove_' . $f])) {
            delete_upload(setting($f));
            $save[$f] = '';
        }
        $up = handle_upload($f, $errors);
        if ($up) {
            delete_upload(setting($f));
            $save[$f] = $up;
        }
    }
    if (($save['background_mode'] === 'image') && empty($save['background_image']) && setting('background_image') === '') {
        $errors[] = 'Modo "imagem" escolhido, mas nenhuma imagem de fundo foi enviada — usando degradê.';
        $save['background_mode'] = 'gradient';
    }
    save_settings($save);
    foreach ($errors as $er) flash($er, 'err');
    flash('Aparência salva. O app aplica na próxima abertura (ou ao tocar em Atualizar).');
    redirect('branding.php');
}

$s = settings();
$presets = [
    'Vermelho clássico' => ['color_primary' => '#E1061B', 'color_accent' => '#FFFFFF', 'color_focus_text' => '#E1061B', 'color_bg1' => '#8E0A16', 'color_bg2' => '#2B0206', 'color_surface' => '#000000', 'color_text' => '#FFFFFF', 'color_text2' => '#F3C9CD', 'color_kids' => '#FFB300'],
    'Azul neon' => ['color_primary' => '#00B4FF', 'color_accent' => '#18E0FF', 'color_focus_text' => '#04121F', 'color_bg1' => '#0A2A5C', 'color_bg2' => '#020814', 'color_surface' => '#06162E', 'color_text' => '#FFFFFF', 'color_text2' => '#9CC9F5', 'color_kids' => '#7CFF6B'],
    'Verde esmeralda' => ['color_primary' => '#10B981', 'color_accent' => '#34D399', 'color_focus_text' => '#022C22', 'color_bg1' => '#064E3B', 'color_bg2' => '#01140F', 'color_surface' => '#000000', 'color_text' => '#FFFFFF', 'color_text2' => '#A7F3D0', 'color_kids' => '#FDE047'],
    'Roxo galáxia' => ['color_primary' => '#8B5CF6', 'color_accent' => '#F0ABFC', 'color_focus_text' => '#2E1065', 'color_bg1' => '#3B0764', 'color_bg2' => '#0B0118', 'color_surface' => '#000000', 'color_text' => '#FFFFFF', 'color_text2' => '#DDD6FE', 'color_kids' => '#FB923C'],
    'Dourado premium' => ['color_primary' => '#D4A017', 'color_accent' => '#FFD54F', 'color_focus_text' => '#1A1300', 'color_bg1' => '#2B2210', 'color_bg2' => '#050403', 'color_surface' => '#000000', 'color_text' => '#FFF8E1', 'color_text2' => '#E8D5A3', 'color_kids' => '#4FC3F7'],
    'Grafite' => ['color_primary' => '#F97316', 'color_accent' => '#FFFFFF', 'color_focus_text' => '#111827', 'color_bg1' => '#1F2937', 'color_bg2' => '#030712', 'color_surface' => '#000000', 'color_text' => '#F9FAFB', 'color_text2' => '#9CA3AF', 'color_kids' => '#22D3EE'],
];

$pageTitle = 'Aparência do aplicativo';
$active = 'branding';
require __DIR__ . '/inc/header.php';
?>
<form method="post" enctype="multipart/form-data">
<?= csrf_field() ?>
<div class="grid g2">
  <div>
    <div class="card">
      <h2>Identidade</h2>
      <div class="field"><label>Nome do app</label><input type="text" name="app_name" maxlength="40" value="<?= e($s['app_name']) ?>"></div>
      <div class="field"><label>Texto da tela de abertura</label><input type="text" name="splash_text" maxlength="120" value="<?= e($s['splash_text']) ?>"></div>
      <div class="grid g2">
        <div class="field"><label>Logo (PNG transparente recomendado)</label>
          <?php if ($s['logo']): ?><img class="thumb" src="<?= e(asset_url($s['logo'])) ?>" alt=""><label class="switch" style="margin:6px 0"><input type="checkbox" name="remove_logo" value="1"> remover</label><?php endif; ?>
          <input type="file" name="logo" accept="image/png,image/jpeg,image/webp"></div>
        <div class="field"><label>Imagem de fundo (1920×1080)</label>
          <?php if ($s['background_image']): ?><img class="thumb" src="<?= e(asset_url($s['background_image'])) ?>" alt=""><label class="switch" style="margin:6px 0"><input type="checkbox" name="remove_background_image" value="1"> remover</label><?php endif; ?>
          <input type="file" name="background_image" accept="image/png,image/jpeg,image/webp"></div>
      </div>
      <div class="field"><label>Tipo de fundo</label>
        <select name="background_mode">
          <?php foreach (['gradient' => 'Degradê (cor 1 → cor 2)', 'image' => 'Imagem de fundo', 'solid' => 'Cor sólida (cor 1)'] as $k => $l): ?>
            <option value="<?= $k ?>" <?= $s['background_mode'] === $k ? 'selected' : '' ?>><?= $l ?></option>
          <?php endforeach; ?>
        </select></div>
    </div>

    <div class="card">
      <h2>Temas prontos</h2>
      <div class="presets">
        <?php foreach ($presets as $name => $p): ?>
          <button type="button" class="preset" data-preset='<?= e(json_encode($p)) ?>'><i style="background:linear-gradient(135deg,<?= $p['color_primary'] ?>,<?= $p['color_bg2'] ?>)"></i><?= e($name) ?></button>
        <?php endforeach; ?>
      </div>
      <p class="hint">Clique em um tema, ajuste se quiser e salve.</p>
    </div>

    <div class="card">
      <h2>Cores</h2>
      <div class="grid g2">
      <?php foreach ($colorKeys as $k => [$label, $hint]): ?>
        <div class="field"><label><?= e($label) ?></label>
          <div class="color"><input type="color" value="<?= e($s[$k]) ?>"><input type="text" name="<?= $k ?>" value="<?= e($s[$k]) ?>" maxlength="7"></div>
          <p class="hint"><?= e($hint) ?></p></div>
      <?php endforeach; ?>
      </div>
      <div class="grid g3">
        <div class="field"><label>Transparência dos painéis <span data-opacity-label></span></label><input type="range" name="surface_opacity" min="0" max="100" value="<?= e($s['surface_opacity']) ?>" style="width:100%"></div>
        <div class="field"><label>Arredondamento (dp)</label><input type="number" name="corner_radius" min="0" max="32" value="<?= e($s['corner_radius']) ?>"></div>
        <div class="field"><label>Escala da interface (%)</label><input type="number" name="ui_scale" min="80" max="130" value="<?= e($s['ui_scale']) ?>"></div>
      </div>
    </div>
    <button class="btn">💾 Salvar aparência</button>
  </div>

  <div>
    <div class="card sticky-preview">
      <h2>Pré-visualização (TV)</h2>
      <div class="tv" id="tvPreview" data-bg="<?= e(asset_url($s['background_image'])) ?>">
        <div class="tbar">
          <div class="tlogo"><?php if ($s['logo']): ?><img src="<?= e(asset_url($s['logo'])) ?>" alt=""><?php endif; ?><span data-name><?= e($s['app_name']) ?></span></div>
          <div class="ticons"><i></i><i></i><i></i><i></i><b>10:24</b></div>
        </div>
        <div class="ttabs"><span class="on"><?= e($s['label_tv']) ?></span><span><?= e($s['label_sports']) ?></span><span><?= e($s['label_highlights']) ?></span><span><?= e($s['label_movies']) ?></span><span><?= e($s['label_series']) ?></span><span class="kids"><?= e($s['label_kids']) ?></span><span><?= e($s['label_anime']) ?></span><span><?= e($s['label_favorites']) ?></span></div>
        <div class="tbody">
          <div class="tlist"><h4>Lista de Canais</h4><div>1 Canal Notícias</div><div class="on">2 Canal Esportes HD</div><div>3 Filmes 24h</div><div>4 Desenhos Kids</div><div>5 Documentários</div><div>6 Música Hits</div></div>
          <div class="tplayer"><div class="tvideo"></div><div class="tinfo"><span>Canal Esportes HD</span><small>10:00–11:50 Programa ao vivo</small></div></div>
        </div>
      </div>
      <p class="hint">A prévia muda conforme você ajusta. No celular o app reorganiza tudo automaticamente.</p>
    </div>
  </div>
</div>
</form>
<?php require __DIR__ . '/inc/footer.php';
