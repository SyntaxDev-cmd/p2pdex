<?php
/** @var string $pageTitle */
/** @var string $active */
$pageTitle = $pageTitle ?? 'Painel';
$active = $active ?? '';
$nav = [
    'index' => ['index.php', 'Visão geral', '▣'],
    'branding' => ['branding.php', 'Aparência', '🎨'],
    'layout' => ['layout.php', 'Abas e menus', '☰'],
    'servers' => ['servers.php', 'Servidores / DNS / P2P', '🛰'],
    'kids' => ['kids.php', 'Kids e controle parental', '🧸'],
    'banners' => ['banners.php', 'Destaques (banners)', '🖼'],
    'messages' => ['messages.php', 'Avisos', '🔔'],
    'devices' => ['devices.php', 'Dispositivos', '📺'],
    'settings' => ['settings.php', 'Sistema e app', '⚙'],
];
$appName = setting('app_name', 'NovaTV');
$logo = setting('logo');
?><!doctype html>
<html lang="pt-br">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title><?= e($pageTitle) ?> · <?= e($appName) ?> Painel</title>
<link rel="stylesheet" href="assets/panel.css?v=<?= PANEL_VERSION ?>">
<style>:root{--brand:<?= e(setting('color_primary', '#E1061B')) ?>}</style>
</head>
<body>
<div class="shell">
  <aside class="side" id="side">
    <div class="brand">
      <?php if ($logo): ?><img src="<?= e(asset_url($logo)) ?>" alt=""><?php else: ?><span class="dot"></span><?php endif; ?>
      <div><strong><?= e($appName) ?></strong><small>Painel de controle</small></div>
    </div>
    <nav>
      <?php foreach ($nav as $key => [$href, $label, $icon]): ?>
        <a href="<?= $href ?>" class="<?= $active === $key ? 'on' : '' ?>"><span class="ic"><?= $icon ?></span><?= e($label) ?></a>
      <?php endforeach; ?>
    </nav>
    <a class="logout" href="logout.php">Sair</a>
  </aside>
  <main class="main">
    <header class="top">
      <button class="burger" onclick="document.getElementById('side').classList.toggle('open')" aria-label="Menu">☰</button>
      <h1><?= e($pageTitle) ?></h1>
      <span class="who">👤 <?= e($_SESSION['admin_user'] ?? '') ?></span>
    </header>
    <div class="content">
    <?php foreach (take_flashes() as $f): ?>
      <div class="alert <?= e($f['type']) ?>"><?= e($f['msg']) ?></div>
    <?php endforeach; ?>
