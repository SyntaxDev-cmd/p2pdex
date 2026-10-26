<?php
/** @var string $pageTitle */
/** @var string $active */
$pageTitle = $pageTitle ?? 'Painel';
$active = $active ?? '';
if (is_admin()) {
    $nav = [
        'index' => ['index.php', 'Visão geral', '▣'],
        'branding' => ['branding.php', 'Aparência', '🎨'],
        'layout' => ['layout.php', 'Abas e ícones', '☰'],
        'servers' => ['servers.php', 'Servidores / DNS / P2P', '🛰'],
        'resellers' => ['resellers.php', 'Revendedores', '🤝'],
        'integrations' => ['integrations.php', 'Jogos do dia e TMDB', '⚽'],
        'kids' => ['kids.php', 'Kids e controle parental', '🧸'],
        'banners' => ['banners.php', 'Destaques (banners)', '🖼'],
        'messages' => ['messages.php', 'Avisos', '🔔'],
        'devices' => ['devices.php', 'Dispositivos', '📺'],
        'security' => ['security.php', 'Segurança e registros', '🛡'],
        'settings' => ['settings.php', 'Sistema e app', '⚙'],
    ];
    $mobileNav = ['index' => 'Início', 'servers' => 'Servidores', 'resellers' => 'Revendas', 'devices' => 'Aparelhos'];
} else {
    $nav = [
        'index' => ['index.php', 'Visão geral', '▣'],
        'servers' => ['servers.php', 'Meus servidores', '🛰'],
        'devices' => ['devices.php', 'Meus dispositivos', '📺'],
        'account' => ['account.php', 'Minha conta', '👤'],
    ];
    $mobileNav = ['index' => 'Início', 'servers' => 'Servidores', 'devices' => 'Aparelhos', 'account' => 'Conta'];
}
$appName = setting('app_name', 'NovaTV');
$logo = setting('logo');
?><!doctype html>
<html lang="pt-br">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex, nofollow">
<meta name="theme-color" content="#0d0f14">
<title><?= e($pageTitle) ?> · <?= e($appName) ?> Painel</title>
<link rel="stylesheet" href="assets/panel.css?v=<?= PANEL_VERSION ?>">
<style>:root{--brand:<?= e(setting('color_primary', '#E1061B')) ?>}</style>
</head>
<body>
<div class="shell">
  <aside class="side" id="side">
    <div class="brand">
      <?php if ($logo): ?><img src="<?= e(asset_url($logo)) ?>" alt=""><?php else: ?><span class="dot"></span><?php endif; ?>
      <div><strong><?= e($appName) ?></strong><small><?= is_admin() ? 'Administrador' : 'Painel da revenda' ?></small></div>
    </div>
    <nav>
      <?php foreach ($nav as $key => [$href, $label, $icon]): ?>
        <a href="<?= $href ?>" class="<?= $active === $key ? 'on' : '' ?>"><span class="ic"><?= $icon ?></span><?= e($label) ?></a>
      <?php endforeach; ?>
    </nav>
    <a class="logout" href="logout.php">Sair</a>
  </aside>
  <div class="scrim" onclick="document.getElementById('side').classList.remove('open')"></div>
  <main class="main">
    <header class="top">
      <button class="burger" onclick="document.getElementById('side').classList.add('open')" aria-label="Menu">☰</button>
      <h1><?= e($pageTitle) ?></h1>
      <span class="who"><span class="role <?= is_admin() ? 'admin' : 'rev' ?>"><?= is_admin() ? 'ADMIN' : 'REVENDA' ?></span> <?= e(current_user()) ?></span>
    </header>
    <div class="content">
    <?php foreach (take_flashes() as $f): ?>
      <div class="alert <?= e($f['type']) ?>"><?= e($f['msg']) ?></div>
    <?php endforeach; ?>
<?php
// barra inferior no celular
$GLOBALS['__mobileNav'] = [];
foreach ($mobileNav as $k => $short) $GLOBALS['__mobileNav'][$k] = [$nav[$k][0], $short, $nav[$k][2]];
