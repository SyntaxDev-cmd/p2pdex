    </div>
    <footer class="foot"><?= e(setting('app_name', 'NovaTV')) ?> · Painel v<?= PANEL_VERSION ?></footer>
  </main>
</div>
<nav class="bottomnav">
  <?php foreach ($GLOBALS['__mobileNav'] ?? [] as $key => [$href, $label, $icon]): ?>
    <a href="<?= $href ?>" class="<?= ($active ?? '') === $key ? 'on' : '' ?>"><span><?= $icon ?></span><small><?= e($label) ?></small></a>
  <?php endforeach; ?>
  <a href="#" onclick="document.getElementById('side').classList.add('open');return false"><span>☰</span><small>Menu</small></a>
</nav>
<script src="assets/panel.js?v=<?= PANEL_VERSION ?>"></script>
</body>
</html>
