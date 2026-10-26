<?php
/**
 * Jogos do dia: baixa a API configurada, guarda em cache e converte para um formato único
 * que o app entende. Detecta os campos automaticamente (time1/mandante/home, canais/canal...)
 * e aceita um mapeamento manual no painel para APIs diferentes.
 */

const GAME_FIELDS = [
    'home' => ['time1', 'time_1', 'mandante', 'home', 'casa', 'team1', 'team_1', 'time_casa', 'home_team', 'hometeam', 'timea', 'equipe1', 'clube1', 'nome_time1', 'time_mandante', 'teamhome'],
    'away' => ['time2', 'time_2', 'visitante', 'away', 'fora', 'team2', 'team_2', 'time_fora', 'away_team', 'awayteam', 'timeb', 'equipe2', 'clube2', 'nome_time2', 'time_visitante', 'teamaway'],
    'home_logo' => ['escudo1', 'escudo_1', 'logo1', 'logo_1', 'home_logo', 'img1', 'imagem1', 'brasao1', 'escudo_mandante', 'logo_mandante', 'icon1', 'logo_time1', 'escudo_time1'],
    'away_logo' => ['escudo2', 'escudo_2', 'logo2', 'logo_2', 'away_logo', 'img2', 'imagem2', 'brasao2', 'escudo_visitante', 'logo_visitante', 'icon2', 'logo_time2', 'escudo_time2'],
    'time' => ['hora', 'horario', 'horário', 'time', 'hour', 'inicio', 'início', 'start', 'data_hora', 'datahora', 'datetime', 'date_time', 'kickoff', 'hora_jogo', 'horario_jogo', 'date', 'data'],
    'league' => ['campeonato', 'liga', 'league', 'competicao', 'competição', 'torneio', 'championship', 'categoria', 'competition', 'tournament', 'camp'],
    'league_logo' => ['logo_campeonato', 'campeonato_logo', 'league_logo', 'logo_liga', 'escudo_campeonato', 'icone_campeonato'],
    'channels' => ['canais', 'canal', 'channels', 'channel', 'transmissao', 'transmissão', 'onde_assistir', 'emissoras', 'emissora', 'tv', 'broadcast', 'transmissoes'],
    'title' => ['jogo', 'partida', 'titulo', 'título', 'title', 'nome', 'name', 'evento', 'event', 'confronto', 'match'],
    'score' => ['placar', 'score', 'resultado'],
    'status' => ['status', 'situacao', 'situação', 'estado'],
];

function games_cache_file(): string
{
    return DATA_DIR . '/games_cache.json';
}

/** @return array{ok:bool,games:array,updated:int,error:string,source:string} */
function games_get(bool $force = false): array
{
    $url = trim(setting('games_api_url'));
    if (setting('games_enabled') !== '1' || $url === '') {
        return ['ok' => false, 'games' => [], 'updated' => 0, 'error' => 'disabled', 'source' => ''];
    }
    $ttl = max(1, (int)setting('games_cache_minutes', '10')) * 60;
    $file = games_cache_file();
    $cached = is_file($file) ? json_decode((string)file_get_contents($file), true) : null;
    if (!$force && is_array($cached) && ($cached['url'] ?? '') === $url && time() - (int)($cached['updated'] ?? 0) < $ttl) {
        return $cached + ['source' => 'cache'];
    }
    $r = http_get($url, '', 15);
    $data = json_decode($r['body'], true);
    if (!is_array($data)) {
        // servidor fora: mantém o último resultado bom
        if (is_array($cached) && !empty($cached['games'])) return $cached + ['source' => 'cache_antigo', 'error' => 'API indisponível agora'];
        return ['ok' => false, 'games' => [], 'updated' => time(), 'error' => 'Resposta inválida da API (HTTP ' . $r['code'] . ($r['error'] ? ', ' . $r['error'] : '') . ')', 'source' => 'erro'];
    }
    $map = json_decode(setting('games_map'), true);
    $games = games_normalize($data, is_array($map) ? $map : []);
    $out = ['ok' => true, 'games' => $games, 'updated' => time(), 'error' => '', 'url' => $url];
    @file_put_contents($file, json_encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES), LOCK_EX);
    return $out + ['source' => 'api'];
}

/** Lê um caminho "a.b.c" num array. */
function arr_path($data, string $path)
{
    if ($path === '') return $data;
    foreach (explode('.', $path) as $k) {
        if (is_array($data) && array_key_exists($k, $data)) $data = $data[$k];
        else return null;
    }
    return $data;
}

function is_list_of_objects($v): bool
{
    if (!is_array($v) || !$v) return false;
    $objs = 0;
    foreach ($v as $x) if (is_array($x) && array_keys($x) !== range(0, count($x) - 1)) $objs++;
    return $objs >= max(1, (int)ceil(count($v) * 0.6));
}

/** Quantos tipos de campo de jogo aparecem nos primeiros itens (times, horário, canal...). */
function game_score(array $list): int
{
    $found = [];
    foreach (array_slice(array_values($list), 0, 5) as $item) {
        if (!is_array($item)) continue;
        $keys = array_keys(flat_item($item));
        foreach (['home', 'away', 'title', 'time', 'channels', 'league'] as $f) {
            foreach (GAME_FIELDS[$f] as $cand) {
                if (in_array($cand, $keys, true) || in_array('__grupo.' . $cand, $keys, true)) { $found[$f] = true; break; }
            }
        }
    }
    // precisa parecer jogo: dois times, ou título + horário/canal
    $looks = (isset($found['home']) && isset($found['away'])) || (isset($found['title']) && (isset($found['time']) || isset($found['channels'])));
    return $looks ? count($found) : 0;
}

/** Junta as listas candidatas (inclusive jogos agrupados por campeonato). */
function collect_lists($data, array &$out, int $depth = 0): void
{
    if (!is_array($data) || $depth > 6) return;
    if (is_list_of_objects($data)) {
        $out[] = array_values(array_filter($data, 'is_array'));
        $byKey = [];
        foreach ($data as $grp) {
            if (!is_array($grp)) continue;
            foreach ($grp as $k => $v) {
                if (is_list_of_objects($v)) {
                    $meta = array_filter($grp, fn($x) => !is_array($x));
                    foreach ($v as $g) if (is_array($g)) $byKey[$k][] = $g + ['__grupo' => $meta];
                }
            }
        }
        foreach ($byKey as $merged) $out[] = $merged;
    }
    foreach ($data as $v) collect_lists($v, $out, $depth + 1);
}

/** Escolhe a lista que mais parece "jogos do dia". */
function find_game_list($data): array
{
    $cands = [];
    collect_lists($data, $cands);
    $best = [];
    $bestScore = 0;
    foreach ($cands as $c) {
        $sc = game_score($c);
        if ($sc > $bestScore || ($sc === $bestScore && $sc > 0 && count($c) > count($best))) {
            $best = $c;
            $bestScore = $sc;
        }
    }
    return $best;
}

/** Achata o item em chaves minúsculas (inclui subníveis com ponto). */
function flat_item(array $item, string $prefix = ''): array
{
    $out = [];
    foreach ($item as $k => $v) {
        $key = mb_strtolower($prefix . $k);
        if (is_array($v) && $v && array_keys($v) !== range(0, count($v) - 1) && $prefix === '') {
            $out[$key] = $v;
            foreach (flat_item($v, $key . '.') as $kk => $vv) $out[$kk] = $vv;
        } else {
            $out[$key] = $v;
        }
    }
    return $out;
}

function pick(array $flat, string $field, array $map)
{
    if (!empty($map[$field])) {
        $v = $flat[mb_strtolower($map[$field])] ?? null;
        if ($v !== null && $v !== '') return $v;
    }
    foreach (GAME_FIELDS[$field] as $cand) {
        if (isset($flat[$cand]) && $flat[$cand] !== '' && $flat[$cand] !== []) return $flat[$cand];
        // grupos (ex.: campeonato no nível de cima)
        if (isset($flat['__grupo.' . $cand]) && $flat['__grupo.' . $cand] !== '') return $flat['__grupo.' . $cand];
    }
    return null;
}

function scalar_name($v): string
{
    if (is_string($v) || is_numeric($v)) return trim((string)$v);
    if (is_array($v)) {
        foreach (['nome', 'name', 'title', 'titulo', 'canal', 'channel', 'sigla'] as $k) {
            if (isset($v[$k]) && is_scalar($v[$k])) return trim((string)$v[$k]);
        }
    }
    return '';
}

function to_channels($v): array
{
    $out = [];
    if (is_string($v)) {
        foreach (preg_split('/\s*(?:,|;|\||\/|\n|\s+e\s+)\s*/u', $v) as $c) if (trim($c) !== '') $out[] = trim($c);
    } elseif (is_array($v)) {
        foreach ($v as $c) {
            $n = scalar_name($c);
            if ($n !== '') $out = array_merge($out, to_channels($n));
        }
    }
    return array_values(array_unique(array_filter($out, fn($c) => mb_strlen($c) <= 40)));
}

function parse_game_time($v): array
{
    $tz = new DateTimeZone('America/Sao_Paulo');
    if (is_numeric($v) && (int)$v > 1000000000) {
        $ts = (int)$v > 20000000000 ? intdiv((int)$v, 1000) : (int)$v;
        return [date('H:i', $ts), $ts];
    }
    $s = trim((string)$v);
    if (preg_match('/(\d{1,2})[:hH](\d{2})/', $s, $m)) {
        $hm = sprintf('%02d:%02d', (int)$m[1], (int)$m[2]);
        $date = new DateTime('now', $tz);
        if (preg_match('/(\d{4})-(\d{2})-(\d{2})/', $s, $d)) $date->setDate((int)$d[1], (int)$d[2], (int)$d[3]);
        elseif (preg_match('/(\d{2})\/(\d{2})(?:\/(\d{2,4}))?/', $s, $d)) {
            $y = isset($d[3]) ? ((int)$d[3] < 100 ? 2000 + (int)$d[3] : (int)$d[3]) : (int)$date->format('Y');
            $date->setDate($y, (int)$d[2], (int)$d[1]);
        }
        $date->setTime((int)$m[1], (int)$m[2]);
        return [$hm, $date->getTimestamp()];
    }
    return [$s, 0];
}

function games_normalize($data, array $map): array
{
    $list = !empty($map['list']) ? arr_path($data, (string)$map['list']) : find_game_list($data);
    if (!is_array($list)) return [];
    $now = time();
    $games = [];
    foreach ($list as $item) {
        if (!is_array($item)) continue;
        $flat = flat_item($item);
        $home = scalar_name(pick($flat, 'home', $map));
        $away = scalar_name(pick($flat, 'away', $map));
        $title = scalar_name(pick($flat, 'title', $map));
        if (($home === '' || $away === '') && $title !== '') {
            $parts = preg_split('/\s+(?:x|vs\.?|versus|×)\s+/iu', $title);
            if (count($parts) === 2) [$home, $away] = array_map('trim', $parts);
        }
        if ($home === '' && $title === '') continue;
        [$hm, $ts] = parse_game_time(pick($flat, 'time', $map) ?? '');
        $status = '';
        $rawStatus = mb_strtolower(scalar_name(pick($flat, 'status', $map)));
        if ($rawStatus !== '' && preg_match('/vivo|live|andamento|1º|2º|interv/u', $rawStatus)) $status = 'live';
        elseif ($rawStatus !== '' && preg_match('/encerr|fim|final|ended|termin/u', $rawStatus)) $status = 'ended';
        elseif ($ts > 0) $status = $now >= $ts && $now < $ts + 2 * 3600 ? 'live' : ($now >= $ts + 2 * 3600 ? 'ended' : 'soon');
        $games[] = [
            'id' => substr(md5($home . $away . $hm . $title), 0, 12),
            'time' => $hm,
            'ts' => $ts,
            'home' => $home,
            'away' => $away,
            'title' => $title !== '' ? $title : trim("$home x $away"),
            'home_logo' => scalar_name(pick($flat, 'home_logo', $map)),
            'away_logo' => scalar_name(pick($flat, 'away_logo', $map)),
            'league' => scalar_name(pick($flat, 'league', $map)),
            'league_logo' => scalar_name(pick($flat, 'league_logo', $map)),
            'channels' => to_channels(pick($flat, 'channels', $map)),
            'score' => scalar_name(pick($flat, 'score', $map)),
            'status' => $status,
        ];
    }
    usort($games, function ($a, $b) {
        $order = ['live' => 0, 'soon' => 1, '' => 2, 'ended' => 3];
        return [$order[$a['status']] ?? 2, $a['ts'] ?: PHP_INT_MAX, $a['time']] <=> [$order[$b['status']] ?? 2, $b['ts'] ?: PHP_INT_MAX, $b['time']];
    });
    return array_slice($games, 0, 300);
}
