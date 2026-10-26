<?php
require __DIR__ . '/inc/bootstrap.php';
require_admin();

$catalog = sports_catalog();

function sp_color(string $v, string $def): string
{
    return preg_match('/^#[0-9a-fA-F]{6}$/', trim($v)) ? strtoupper(trim($v)) : $def;
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $op = $_POST['op'] ?? '';
    if ($op === 'brand') {
        $errors = [];
        $save = [
            'sports_app_name' => mb_substr(trim((string)($_POST['sports_app_name'] ?? '')), 0, 40) ?: 'NovaSports',
            'sports_welcome' => mb_substr(trim((string)($_POST['sports_welcome'] ?? '')), 0, 120),
            'sports_color_primary' => sp_color((string)($_POST['sports_color_primary'] ?? ''), '#19C463'),
            'sports_color_accent' => sp_color((string)($_POST['sports_color_accent'] ?? ''), '#B6FF3B'),
            'sports_color_bg1' => sp_color((string)($_POST['sports_color_bg1'] ?? ''), '#0B6B35'),
            'sports_color_bg2' => sp_color((string)($_POST['sports_color_bg2'] ?? ''), '#02160B'),
            'sports_color_text2' => sp_color((string)($_POST['sports_color_text2'] ?? ''), '#CFF5D9'),
            'sports_keywords' => mb_substr(trim((string)($_POST['sports_keywords'] ?? '')), 0, 600),
            'sports_latest_version_code' => (string)max(0, (int)($_POST['sports_latest_version_code'] ?? 0)),
            'sports_update_url' => trim((string)($_POST['sports_update_url'] ?? '')),
            'sports_force_update' => !empty($_POST['sports_force_update']) ? '1' : '0',
        ];
        $up = handle_upload('sports_logo', $errors);
        if ($up) {
            delete_upload(setting('sports_logo'));
            $save['sports_logo'] = $up;
        } elseif (!empty($_POST['remove_logo'])) {
            delete_upload(setting('sports_logo'));
            $save['sports_logo'] = '';
        }
        foreach ($errors as $er) flash($er, 'err');
        save_settings($save);
        audit('sports_app_salvo');
        flash('App de esportes salvo. Os aparelhos recebem na próxima abertura.');
        redirect('sports.php');
    }
    if ($op === 'leagues') {
        $chosen = [];
        foreach ($catalog as $code => $name) {
            if (empty($_POST['lg'][$code])) continue;
            $label = mb_substr(trim((string)($_POST['name'][$code] ?? '')), 0, 30) ?: $name;
            $chosen[] = ['code' => $code, 'name' => $label, 'pos' => (int)($_POST['pos'][$code] ?? 99)];
        }
        // ligas extras digitadas: "codigo | Nome"
        foreach (lines((string)($_POST['custom'] ?? '')) as $i => $line) {
            [$code, $name] = array_map('trim', array_pad(explode('|', $line, 2), 2, ''));
            if (!preg_match('/^[a-z0-9_.]+$/i', $code)) continue;
            $chosen[] = ['code' => strtolower($code), 'name' => mb_substr($name ?: $code, 0, 30), 'pos' => 100 + $i];
        }
        usort($chosen, fn($a, $b) => $a['pos'] <=> $b['pos']);
        save_settings(['sports_leagues' => json_encode(array_map(fn($l) => ['code' => $l['code'], 'name' => $l['name']], $chosen), JSON_UNESCAPED_UNICODE)]);
        flash(count($chosen) . ' ligas salvas.');
        redirect('sports.php');
    }
    if ($op === 'reset_leagues') {
        save_settings(['sports_leagues' => '']);
        flash('Ligas voltaram ao padrão do app.');
        redirect('sports.php');
    }
}

$s = settings(true);
$current = sports_leagues();
$byCode = [];
foreach ($current as $i => $l) $byCode[$l['code']] = ['name' => $l['name'], 'pos' => $i + 1];
$usingDefault = !$current;
$i = 0;
$custom = [];
foreach ($current as $l) if (!isset($catalog[$l['code']])) $custom[] = $l['code'] . ' | ' . $l['name'];

$pageTitle = 'App Esportes (NovaSports)';
$active = 'sports';
require __DIR__ . '/inc/header.php';
?>
<style>
  .sp-hero{display:flex;gap:18px;align-items:center;padding:20px;border-radius:16px;margin-bottom:18px;
    background:linear-gradient(120deg,<?= e($s['sports_color_bg1']) ?>,<?= e($s['sports_color_bg2']) ?>);border:1px solid #ffffff1f}
  .sp-hero .ball{width:64px;height:64px;border-radius:18px;display:flex;align-items:center;justify-content:center;font-size:34px;
    background:linear-gradient(160deg,<?= e($s['sports_color_primary']) ?>,#05301a);box-shadow:0 8px 24px #0006;overflow:hidden}
  .sp-hero .ball img{width:100%;height:100%;object-fit:contain}
  .sp-hero h2{margin:0;font-size:22px;color:#fff}
  .sp-hero p{margin:4px 0 0;color:<?= e($s['sports_color_text2']) ?>}
  .sp-hero .links{margin-left:auto;display:flex;gap:8px;flex-wrap:wrap}
  .sp-hero .btn{background:<?= e($s['sports_color_accent']) ?>;color:#05210f}
  .lg-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));gap:10px}
  .lg{display:flex;align-items:center;gap:10px;padding:10px 12px;border:1px solid var(--line);border-radius:12px;background:#0f131b}
  .lg.on{border-color:<?= e($s['sports_color_primary']) ?>;background:color-mix(in srgb,<?= e($s['sports_color_primary']) ?> 12%,#0f131b)}
  .lg input[type=text]{padding:6px 8px;font-size:13px}
  .lg input[type=number]{width:58px;padding:6px;font-size:13px}
  .lg code{font-size:11px;color:var(--muted)}
  .sp-features{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:10px;margin-top:6px}
  .sp-features div{padding:12px;border-radius:12px;background:#ffffff08;border:1px solid var(--line);font-size:13px}
  .sp-features b{display:block;color:#fff;margin-bottom:2px}
</style>

<div class="sp-hero">
  <div class="ball"><?php if ($s['sports_logo']): ?><img src="<?= e(asset_url($s['sports_logo'])) ?>" alt=""><?php else: ?>⚽<?php endif; ?></div>
  <div>
    <h2><?= e($s['sports_app_name']) ?></h2>
    <p>App só de esportes, ligado a este mesmo painel: mesmos servidores, revendas, aparelhos e logins.</p>
  </div>
  <div class="links">
    <a class="btn small" href="https://github.com/SyntaxDev-cmd/p2pdex/releases/download/sports-teste/NovaSports.apk" target="_blank" rel="noopener">⬇ APK de teste</a>
  </div>
</div>

<div class="card">
  <h2>O que o app mostra</h2>
  <div class="sp-features">
    <div><b>Ao vivo</b>Placar em tempo real (ESPN), atualiza a cada 30 s. Tocar abre o canal que transmite.</div>
    <div><b>Jogos</b>Agenda de ontem até 5 dias à frente, por liga.</div>
    <div><b>Canais</b>Só as categorias de esporte da lista do cliente (palavras-chave abaixo).</div>
    <div><b>Notícias</b>ESPN Brasil em português, com foto e matéria completa.</div>
    <div><b>Tabela</b>Classificação com zonas de Libertadores, pré e rebaixamento.</div>
    <div><b>Canais dos jogos</b>Vêm de "Jogos do dia" (Integrações) + ESPN.</div>
  </div>
</div>

<div class="grid g2">
<form method="post" enctype="multipart/form-data" class="card">
  <?= csrf_field() ?><input type="hidden" name="op" value="brand">
  <h2>Marca e cores do app</h2>
  <div class="grid g2">
    <div class="field"><label>Nome do app</label><input type="text" name="sports_app_name" value="<?= e($s['sports_app_name']) ?>"></div>
    <div class="field"><label>Frase do login</label><input type="text" name="sports_welcome" value="<?= e($s['sports_welcome']) ?>"></div>
  </div>
  <div class="field"><label>Logo (PNG com fundo transparente)</label>
    <?php if ($s['sports_logo']): ?><img class="thumb" src="<?= e(asset_url($s['sports_logo'])) ?>" alt=""><label class="switch" style="margin:6px 0"><input type="checkbox" name="remove_logo" value="1"> remover</label><?php endif; ?>
    <input type="file" name="sports_logo" accept="image/*"></div>
  <div class="grid g3">
    <div class="field"><label>Principal</label><input type="color" name="sports_color_primary" value="<?= e($s['sports_color_primary']) ?>"></div>
    <div class="field"><label>Destaque (foco)</label><input type="color" name="sports_color_accent" value="<?= e($s['sports_color_accent']) ?>"></div>
    <div class="field"><label>Texto secundário</label><input type="color" name="sports_color_text2" value="<?= e($s['sports_color_text2']) ?>"></div>
    <div class="field"><label>Fundo (topo)</label><input type="color" name="sports_color_bg1" value="<?= e($s['sports_color_bg1']) ?>"></div>
    <div class="field"><label>Fundo (base)</label><input type="color" name="sports_color_bg2" value="<?= e($s['sports_color_bg2']) ?>"></div>
  </div>
  <div class="field"><label>Palavras extras para achar canais de esporte (vírgula)</label>
    <input type="text" name="sports_keywords" value="<?= e($s['sports_keywords']) ?>" placeholder="ex.: cazé tv, nosso futebol, xsports">
    <p class="hint">Somadas às de "Kids e controle parental → Esportes". O app esconde tudo que não for esporte.</p></div>
  <h2 style="margin-top:8px">Atualização do app de esportes</h2>
  <div class="grid g2">
    <div class="field"><label>Última versão (número do build)</label><input type="number" name="sports_latest_version_code" value="<?= (int)$s['sports_latest_version_code'] ?>"></div>
    <div class="field"><label>Link do APK</label><input type="text" name="sports_update_url" value="<?= e($s['sports_update_url']) ?>" placeholder="https://.../NovaSports.apk"></div>
  </div>
  <label class="switch"><input type="checkbox" name="sports_force_update" value="1" <?= $s['sports_force_update'] === '1' ? 'checked' : '' ?>> Obrigar atualização</label>
  <div class="row" style="margin-top:14px"><button class="btn">💾 Salvar</button></div>
</form>

<form method="post" class="card">
  <?= csrf_field() ?><input type="hidden" name="op" value="leagues">
  <h2>Ligas acompanhadas <?= $usingDefault ? '<span class="badge">padrão do app</span>' : '<span class="badge on">' . count($current) . ' escolhidas</span>' ?></h2>
  <p class="hint" style="margin-bottom:12px">Marque as ligas, ajuste o nome e a ordem (1 = primeiro). Elas aparecem nos filtros de Jogos, Notícias e Tabela.</p>
  <div class="lg-grid">
    <?php foreach ($catalog as $code => $name):
        $on = $usingDefault ? $i < 18 : isset($byCode[$code]);
        $pos = $byCode[$code]['pos'] ?? ($i + 1); $i++; ?>
      <label class="lg <?= $on ? 'on' : '' ?>">
        <input type="checkbox" name="lg[<?= e($code) ?>]" value="1" <?= $on ? 'checked' : '' ?>>
        <span style="flex:1;min-width:0"><input type="text" name="name[<?= e($code) ?>]" value="<?= e($byCode[$code]['name'] ?? $name) ?>"><code><?= e($code) ?></code></span>
        <input type="number" name="pos[<?= e($code) ?>]" value="<?= (int)$pos ?>" min="1" max="99" title="Ordem">
      </label>
    <?php endforeach; ?>
  </div>
  <div class="field" style="margin-top:14px"><label>Outras ligas da ESPN (uma por linha: código | Nome)</label>
    <textarea name="custom" placeholder="mex.1 | Liga MX&#10;tur.1 | Turquia"><?= e(implode("\n", $custom)) ?></textarea></div>
  <div class="row"><button class="btn">💾 Salvar ligas</button></div>
</form>
</div>
<form method="post" style="margin-top:-6px"><?= csrf_field() ?><input type="hidden" name="op" value="reset_leagues"><button class="btn ghost small">↺ Voltar ligas ao padrão</button></form>
<script>
document.querySelectorAll('.lg input[type=checkbox]').forEach(c => c.addEventListener('change', () => c.closest('.lg').classList.toggle('on', c.checked)));
</script>
<?php require __DIR__ . '/inc/footer.php';
