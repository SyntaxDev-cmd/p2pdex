<?php
require __DIR__ . '/inc/bootstrap.php';
require_login();

$pdo = db();
$types = ['xtream' => 'Xtream Codes (DNS)', 'p2p' => 'P2P (API compatível Xtream)', 'm3u' => 'Lista M3U'];

function normalize_url(string $u): string
{
    $u = trim($u);
    if ($u === '') return '';
    if (!preg_match('#^https?://#i', $u)) $u = 'http://' . $u;
    // aceita a DNS colada com /player_api.php, ?username=... ou barra no fim
    $u = preg_replace('#[?\#].*$#', '', $u);
    $u = preg_replace('#/(player_api|get|panel_api|xmltv)\.php$#i', '', rtrim($u, '/'));
    return rtrim($u, '/');
}

$action = $_GET['action'] ?? '';
$id = (int)($_GET['id'] ?? 0);
$testResult = null;
$rid = reseller_id();

/** Revenda só mexe nos próprios servidores. */
function owned_server(int $id): ?array
{
    $st = db()->prepare('SELECT * FROM servers WHERE id=?');
    $st->execute([$id]);
    $s = $st->fetch();
    if (!$s) return null;
    if (!is_admin() && (int)$s['reseller_id'] !== reseller_id()) return null;
    return $s;
}

if ($id && !owned_server($id)) {
    flash('Servidor não encontrado.', 'err');
    redirect('servers.php');
}
$me = is_admin() ? null : reseller($rid);
$myCount = (int)(function () use ($pdo, $rid) {
    $st = $pdo->prepare('SELECT COUNT(*) FROM servers WHERE reseller_id=?');
    $st->execute([$rid]);
    return $st->fetchColumn();
})();
$canAdd = is_admin() || ($me && $myCount < (int)$me['max_servers']);

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $op = $_POST['op'] ?? '';
    if ($op === 'save') {
        $type = isset($types[$_POST['type'] ?? '']) ? $_POST['type'] : 'xtream';
        $urls = array_map('normalize_url', lines((string)($_POST['urls'] ?? '')));
        $data = [
            'name' => mb_substr(trim((string)($_POST['name'] ?? '')), 0, 60) ?: 'Servidor',
            'type' => $type,
            'urls' => implode("\n", array_filter($urls)),
            'm3u_url' => trim((string)($_POST['m3u_url'] ?? '')),
            'epg_url' => trim((string)($_POST['epg_url'] ?? '')),
            'user_agent' => mb_substr(trim((string)($_POST['user_agent'] ?? '')), 0, 200),
            'output' => in_array($_POST['output'] ?? '', ['ts', 'm3u8'], true) ? $_POST['output'] : 'ts',
            'p2p_email' => trim((string)($_POST['p2p_email'] ?? '')),
            'p2p_lib' => normalize_url((string)($_POST['p2p_lib'] ?? '')),
            'p2p_template' => trim((string)($_POST['p2p_template'] ?? '')),
            'priority' => (int)($_POST['priority'] ?? 0),
            'active' => !empty($_POST['active']) ? 1 : 0,
            'notes' => mb_substr(trim((string)($_POST['notes'] ?? '')), 0, 500),
            'reseller_id' => is_admin() ? max(0, (int)($_POST['owner'] ?? 0)) : $rid,
        ];
        if (!$id && !$canAdd) {
            flash('Limite de servidores da sua revenda atingido.', 'err');
            redirect('servers.php');
        }
        $err = '';
        if ($type === 'm3u' && $data['m3u_url'] === '') $err = 'Informe a URL da lista M3U.';
        if ($type !== 'm3u' && $data['urls'] === '') $err = 'Informe pelo menos uma DNS/URL.';
        if ($err) {
            flash($err, 'err');
            redirect('servers.php?action=' . ($id ? 'edit&id=' . $id : 'new'));
        }
        if ($id) {
            $set = implode(',', array_map(fn($k) => "$k=:$k", array_keys($data)));
            $data['id'] = $id;
            $pdo->prepare("UPDATE servers SET $set WHERE id=:id")->execute($data);
        } else {
            $cols = implode(',', array_keys($data));
            $ph = implode(',', array_map(fn($k) => ":$k", array_keys($data)));
            $pdo->prepare("INSERT INTO servers ($cols) VALUES ($ph)")->execute($data);
        }
        audit('servidor_salvo', $data['name']);
        flash('Servidor salvo.');
        redirect('servers.php');
    }
    if ($op === 'delete' && $id) {
        $pdo->prepare('DELETE FROM servers WHERE id=?')->execute([$id]);
        audit('servidor_excluido', (string)$id);
        flash('Servidor removido.');
        redirect('servers.php');
    }
    if ($op === 'toggle' && $id) {
        $pdo->prepare('UPDATE servers SET active = 1 - active WHERE id=?')->execute([$id]);
        redirect('servers.php');
    }
    if ($op === 'test' && $id) {
        $st = $pdo->prepare('SELECT * FROM servers WHERE id=?');
        $st->execute([$id]);
        $srv = $st->fetch();
        $user = trim((string)($_POST['test_user'] ?? ''));
        $pass = trim((string)($_POST['test_pass'] ?? ''));
        $testResult = ['server' => $srv, 'rows' => []];
        if ($srv && $srv['type'] === 'm3u') {
            $url = str_replace(['{username}', '{password}'], [rawurlencode($user), rawurlencode($pass)], $srv['m3u_url']);
            $r = http_get($url, $srv['user_agent'] ?: setting('default_user_agent'), 25);
            $count = substr_count($r['body'], '#EXTINF');
            $testResult['rows'][] = ['url' => preg_replace('#(password=)[^&]+#', '$1***', $url), 'ok' => $count > 0, 'ms' => $r['ms'], 'info' => $count > 0 ? "$count itens na lista" : ('HTTP ' . $r['code'] . ' ' . $r['error'])];
        } elseif ($srv) {
            $u = $user . (str_starts_with($srv['p2p_email'], '@') ? $srv['p2p_email'] : '');
            foreach (lines($srv['urls']) as $base) {
                $r = http_get($base . '/player_api.php?username=' . rawurlencode($u) . '&password=' . rawurlencode($pass), $srv['user_agent'] ?: setting('default_user_agent'));
                $j = json_decode($r['body'], true);
                $ok = is_array($j) && (int)($j['user_info']['auth'] ?? 0) === 1;
                if ($ok) {
                    $ui = $j['user_info'];
                    $exp = !empty($ui['exp_date']) ? date('d/m/Y', (int)$ui['exp_date']) : 'sem validade';
                    $info = 'Status: ' . ($ui['status'] ?? '?') . ' · expira ' . $exp . ' · conexões ' . ($ui['active_cons'] ?? '0') . '/' . ($ui['max_connections'] ?? '?');
                } elseif (is_array($j)) {
                    $info = 'Servidor respondeu, mas login recusado';
                } else {
                    $info = 'HTTP ' . $r['code'] . ($r['error'] ? ' · ' . $r['error'] : '') . ' · resposta não é API Xtream';
                }
                $testResult['rows'][] = ['url' => $base, 'ok' => $ok, 'ms' => $r['ms'], 'info' => $info];
            }
        }
    }
}

$editing = null;
if (($action === 'edit' && $id) || $action === 'new') {
    if ($id) {
        $st = $pdo->prepare('SELECT * FROM servers WHERE id=?');
        $st->execute([$id]);
        $editing = $st->fetch() ?: null;
    }
    if (!$editing) {
        if (!$canAdd) {
            flash('Limite de servidores da sua revenda atingido.', 'err');
            redirect('servers.php');
        }
        $editing = ['id' => 0, 'reseller_id' => $rid, 'name' => '', 'type' => 'xtream', 'urls' => '', 'm3u_url' => '', 'epg_url' => '', 'user_agent' => '', 'output' => 'ts', 'p2p_email' => '', 'p2p_lib' => '', 'p2p_template' => '', 'priority' => 0, 'active' => 1, 'notes' => ''];
    }
}
if (is_admin()) {
    $servers = $pdo->query('SELECT s.*, r.name AS owner FROM servers s LEFT JOIN resellers r ON r.id=s.reseller_id ORDER BY s.reseller_id, s.priority DESC, s.id')->fetchAll();
    $owners = $pdo->query('SELECT id, name FROM resellers ORDER BY name')->fetchAll();
} else {
    $st = $pdo->prepare('SELECT s.*, NULL AS owner FROM servers s WHERE reseller_id=? ORDER BY priority DESC, id');
    $st->execute([$rid]);
    $servers = $st->fetchAll();
    $owners = [];
}

$pageTitle = is_admin() ? 'Servidores, DNS e P2P' : 'Meus servidores';
$active = 'servers';
require __DIR__ . '/inc/header.php';
?>
<?php if ($editing): ?>
<form method="post" action="servers.php?id=<?= (int)$editing['id'] ?>" class="card" id="srvForm">
  <?= csrf_field() ?><input type="hidden" name="op" value="save">
  <h2><?= $editing['id'] ? 'Editar servidor' : 'Novo servidor' ?></h2>
  <div class="grid g3">
    <div class="field"><label>Nome</label><input type="text" name="name" value="<?= e($editing['name']) ?>" required placeholder="Ex.: Servidor Principal"></div>
    <div class="field"><label>Tipo de conexão</label>
      <select name="type" id="srvType">
        <?php foreach ($types as $k => $l): ?><option value="<?= $k ?>" <?= $editing['type'] === $k ? 'selected' : '' ?>><?= $l ?></option><?php endforeach; ?>
      </select></div>
    <div class="field"><label>Prioridade (maior = primeiro)</label><input type="number" name="priority" value="<?= (int)$editing['priority'] ?>"></div>
  </div>
  <?php if (is_admin()): ?>
  <div class="field"><label>Dono do servidor</label>
    <select name="owner">
      <option value="0">Administrador (clientes sem código de revenda)</option>
      <?php foreach ($owners as $o): ?><option value="<?= $o['id'] ?>" <?= (int)($editing['reseller_id'] ?? 0) === (int)$o['id'] ? 'selected' : '' ?>>Revenda: <?= e($o['name']) ?></option><?php endforeach; ?>
    </select></div>
  <?php endif; ?>

  <div class="field" data-for="xtream p2p"><label data-label-xtream="DNS do servidor (uma por linha — o app troca sozinho se uma cair)" data-label-p2p="API Auth (domínio da API P2P — uma por linha para failover)">DNS</label>
    <textarea name="urls" placeholder="http://dns1.exemplo.com:80&#10;http://dns2.exemplo.com:8080"><?= e($editing['urls']) ?></textarea>
    <p class="hint">Pode colar com ou sem http://. O app testa na ordem e memoriza a mais rápida que respondeu.</p></div>

  <div class="grid g2" data-for="p2p">
    <div class="field"><label>API Email (sufixo do login, opcional)</label><input type="text" name="p2p_email" value="<?= e($editing['p2p_email']) ?>" placeholder="@seudominio.site">
      <p class="hint">Se começar com @, é adicionado ao usuário digitado (ex.: 123 → 123@seudominio.site).</p></div>
    <div class="field"><label>Domínio LIB (servidor dos streams)</label><input type="text" name="p2p_lib" value="<?= e($editing['p2p_lib']) ?>" placeholder="lib.seudominio.live">
      <p class="hint">Se vazio, usa a mesma API Auth.</p></div>
  </div>

  <div class="field" data-for="m3u"><label>URL da lista M3U</label><input type="text" name="m3u_url" value="<?= e($editing['m3u_url']) ?>" placeholder="http://servidor/get.php?username={username}&password={password}&type=m3u_plus&output=ts">
    <p class="hint">Use {username} e {password} para o app trocar pelo login do cliente.</p></div>

  <div class="grid g3">
    <div class="field" data-for="xtream p2p"><label>Formato do ao vivo</label>
      <select name="output"><option value="ts" <?= $editing['output'] === 'ts' ? 'selected' : '' ?>>MPEG-TS (.ts) — mais compatível</option><option value="m3u8" <?= $editing['output'] === 'm3u8' ? 'selected' : '' ?>>HLS (.m3u8)</option></select></div>
    <div class="field"><label>EPG XMLTV (opcional)</label><input type="text" name="epg_url" value="<?= e($editing['epg_url']) ?>" placeholder="Vazio = EPG do próprio servidor"></div>
    <div class="field"><label>User-Agent (opcional)</label><input type="text" name="user_agent" value="<?= e($editing['user_agent']) ?>" placeholder="Padrão do app"></div>
  </div>

  <details class="field" data-for="xtream p2p">
    <summary style="cursor:pointer;color:var(--muted)">Avançado: modelo de URL do stream</summary>
    <input type="text" name="p2p_template" value="<?= e($editing['p2p_template']) ?>" placeholder="{base}/{kind}/{user}/{pass}/{id}.{ext}" style="margin-top:8px">
    <p class="hint">Variáveis: {base} (LIB ou DNS ativa), {kind} (live/movie/series), {user}, {pass}, {id}, {ext}. Deixe vazio para o padrão Xtream.</p>
  </details>

  <div class="field"><label>Observações internas</label><input type="text" name="notes" value="<?= e($editing['notes']) ?>"></div>
  <label class="switch" style="margin-bottom:16px"><input type="checkbox" name="active" value="1" <?= $editing['active'] ? 'checked' : '' ?>> Servidor ativo</label>
  <div class="row"><button class="btn">💾 Salvar</button><a class="btn ghost" href="servers.php">Cancelar</a></div>
</form>
<script>
(function(){
  const sel = document.getElementById('srvType');
  function sync(){
    const t = sel.value;
    document.querySelectorAll('#srvForm [data-for]').forEach(el => { el.style.display = el.dataset.for.split(' ').includes(t) ? '' : 'none'; });
    document.querySelectorAll('#srvForm [data-label-' + t + ']').forEach(el => el.textContent = el.getAttribute('data-label-' + t));
  }
  sel.addEventListener('change', sync); sync();
})();
</script>
<?php else: ?>

<?php if ($testResult): ?>
<div class="card">
  <h2>Resultado do teste — <?= e($testResult['server']['name'] ?? '') ?></h2>
  <div class="tbl"><table><tr><th>DNS / URL</th><th>Status</th><th>Tempo</th><th>Detalhes</th></tr>
  <?php foreach ($testResult['rows'] as $r): ?>
    <tr><td class="mono"><?= e($r['url']) ?></td><td><span class="badge <?= $r['ok'] ? 'on' : 'off' ?>"><?= $r['ok'] ? 'ONLINE' : 'FALHOU' ?></span></td><td><?= (int)$r['ms'] ?> ms</td><td><?= e($r['info']) ?></td></tr>
  <?php endforeach; ?>
  </table></div>
  <p class="hint">O login de teste não é salvo.</p>
</div>
<?php endif; ?>

<div class="card">
  <div class="row" style="justify-content:space-between;margin-bottom:12px"><h2 style="margin:0">Servidores cadastrados<?= $me ? ' <small class="hint">(' . $myCount . ' de ' . (int)$me['max_servers'] . ')</small>' : '' ?></h2>
    <?php if ($canAdd): ?><a class="btn" href="servers.php?action=new">+ Novo servidor</a><?php else: ?><span class="badge off">Limite atingido</span><?php endif; ?></div>
  <?php if (!$servers): ?><div class="empty">Nenhum servidor ainda. Cadastre sua DNS Xtream, seu P2P ou uma lista M3U.</div><?php else: ?>
  <div class="tbl"><table>
    <tr><th>Nome</th><th>Tipo</th><th>Endereços</th><?php if (is_admin()): ?><th>Dono</th><?php endif; ?><th>Prioridade</th><th>Status</th><th></th></tr>
    <?php foreach ($servers as $s): $n = count(lines($s['urls'])); ?>
    <tr>
      <td><strong><?= e($s['name']) ?></strong><?php if ($s['notes']): ?><br><small class="hint"><?= e($s['notes']) ?></small><?php endif; ?></td>
      <td><span class="badge <?= $s['type'] === 'p2p' ? 'p2p' : '' ?>"><?= e(strtoupper($s['type'])) ?></span></td>
      <td class="mono" data-label="Endereços"><?= $s['type'] === 'm3u' ? 'lista M3U' : ($n . ' DNS' . ($s['p2p_lib'] ? ' + LIB' : '')) ?></td>
      <?php if (is_admin()): ?><td data-label="Dono"><?= $s['owner'] ? '🤝 ' . e($s['owner']) : 'Admin' ?></td><?php endif; ?>
      <td><?= (int)$s['priority'] ?></td>
      <td><form method="post" action="servers.php?id=<?= $s['id'] ?>" style="display:inline"><?= csrf_field() ?><input type="hidden" name="op" value="toggle"><button class="badge <?= $s['active'] ? 'on' : 'off' ?>" style="border:0;cursor:pointer"><?= $s['active'] ? 'Ativo' : 'Inativo' ?></button></form></td>
      <td class="row" style="justify-content:flex-end">
        <a class="btn small ghost" href="servers.php?action=edit&id=<?= $s['id'] ?>">Editar</a>
        <form method="post" action="servers.php?id=<?= $s['id'] ?>" style="display:inline"><?= csrf_field() ?><input type="hidden" name="op" value="delete"><button class="btn small danger" data-confirm="Remover este servidor?">Excluir</button></form>
      </td>
    </tr>
    <tr class="sub"><td colspan="7">
      <form method="post" action="servers.php?id=<?= $s['id'] ?>" class="row"><?= csrf_field() ?><input type="hidden" name="op" value="test">
        <input type="text" name="test_user" placeholder="usuário de teste" style="max-width:200px"><input type="password" name="test_pass" placeholder="senha de teste" style="max-width:200px">
        <button class="btn small ghost">⚡ Testar conexão</button></form>
    </td></tr>
    <?php endforeach; ?>
  </table></div>
  <?php endif; ?>
</div>

<div class="card">
  <h2>Como o app conecta</h2>
  <p class="hint">1) O app baixa a lista de servidores ativos deste painel. 2) O cliente digita usuário e senha (e escolhe o servidor, se a opção estiver ligada em Sistema). 3) O app testa as DNS em paralelo e usa a primeira que autenticar; se ela cair durante o uso, troca para a próxima automaticamente. 4) Para P2P, login e listas vêm da API Auth e o vídeo vem do Domínio LIB.</p>
</div>
<?php endif; ?>
<?php require __DIR__ . '/inc/footer.php';
