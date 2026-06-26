extends Node3D
## Relic Rush — a 3D two-lane city endless runner.
##
## The entire world (camera, lights, player, obstacles, scenery, weather and UI)
## is built procedurally from Godot primitive meshes, so the project has no
## imported art assets and is fully original. Drop in real 3D models later and
## swap them for the blockout meshes built here.

# ---- Tuning -------------------------------------------------------------------
const LANE_X := 1.15          # half-distance between the two lanes
const SPAWN_Z := -70.0        # how far ahead things appear
const CULL_Z := 12.0          # behind the camera -> recycle / remove
const START_SPEED := 9.0      # gentle start
const MAX_SPEED := 33.0       # ramps to a fast, demanding top speed
const ACCEL := 0.38           # gradual but persistent speed-up
const GRACE := 3.0
const TUTORIAL_TIME := 10.0
const GAP_Z := 22.0           # more spacing so the next obstacle isn't on top of you
const JUMP_VELOCITY := 8.5    # higher hop...
const GRAVITY := -28.0        # ...but strong gravity keeps air time short (~0.6 s)
const JUMP_CLEAR := 0.6
const SLIDE_TIME := 0.7
const WEATHER_FADE := 5.0
const COIN_VALUE := 10
const POWERUP_TIME := 10.0
const SAVE_PATH := "user://relicrush.save"

enum St { DEMO, PLAYING, OVER }

# ---- Runtime state ------------------------------------------------------------
var state: int = St.DEMO
var speed := START_SPEED
var dist_to_spawn := 0.0
var game_time := 0.0
var ui_time := 0.0
var run_cycle := 0.0
var score := 0
var coins := 0
var high := 0
var crash_flash := 0.0

# player
var p_lane := 0
var p_x := -LANE_X
var p_x_vel := 0.0          # lane-change velocity (smooth-damp)
var p_y := 0.0
var p_vy := 0.0
var jumping := false
var sliding := false
var slide_t := 0.0

# nodes
var cam: Camera3D
var sun: DirectionalLight3D
var env: Environment
var sky_mat: PanoramaSkyMaterial
var player: Node3D
var upper: Node3D          # upper-body group (leans forward when sliding)
var arm_l_piv: Node3D
var arm_r_piv: Node3D
var leg_l_piv: Node3D
var leg_r_piv: Node3D
var scarf: Node3D
var board: Node3D

# Imported CC0 models (Kenney)
var runner_scene: PackedScene
var runner_model: Node3D
var runner_anim: AnimationPlayer
var jog_clip := ""
var car_scenes: Array = []
var cone_scene: PackedScene
var building_scenes: Array = []
var streetlights: Array = []
var light_timer := 0.0

# Audio (CC0, Kenney)
var sfx_coin: AudioStreamPlayer
var sfx_jump: AudioStreamPlayer
var sfx_slide: AudioStreamPlayer
var sfx_crash: AudioStreamPlayer
var sfx_thunder: AudioStreamPlayer
var sfx_powerup: AudioStreamPlayer
var sfx_gameover: AudioStreamPlayer
var music: AudioStreamPlayer

# Storm / tornado / lightning
var lightning_flash := 0.0
var lightning_timer := 3.0
var bolt_timer := 0.0
var bolt: Node3D
var tornado: Node3D
var flash_rect: ColorRect

var obstacles: Array = []      # [{node, type, lane, resolved}]
var coin_nodes: Array = []     # [{node, lane, taken}]
var powerups: Array = []       # [{node, lane, type, taken}]
var shield_t := 0.0
var magnet_t := 0.0
var mult_t := 0.0
var jet_t := 0.0
var tank_t := 0.0
var gun_t := 0.0
var gun_timer := 0.0
var powerup_cd := 0.0     # min spacing between power-ups (no back-to-back)
var highlight_t := 0.0       # cinematic camera highlight on pickup
var shield_bubble: Node3D
var tank_model: Node3D
var gun_model: Node3D
var jet_flame: Node3D
var sparks: Array = []         # destroy-effect sparks [{node, vel, life}]

# Biomes / scenery
var biomes: Array = []
var biome_idx := 0
var biome_timer := 32.0
var surround_mi: MeshInstance3D
var tree_scenes: Array = []
var palm_scenes: Array = []
var desert_scenes: Array = []
var scatter_lib := {}          # name -> PackedScene of small ground props
var scatter_pool: Array = []   # dense small props near the road
var scatter_timer := 0.0
var water_plane: Node3D
var debris: Array = []          # tornado flying debris [{node, vel, spin}]
var debris_timer := 0.0
var sand_root: Node3D
var sand_bits: Array = []
var dust: Array = []           # running foot dust [{node, vel, life}]
var dust_timer := 0.0
var dashes: Array = []
var buildings: Array = []
var build_timer := 0.0
var rain_root: Node3D
var snow_root: Node3D
var rain_drops: Array = []
var snow_flakes: Array = []

# demo
var demo_timer := 0.0
var demo_index := 0
var demo_caption := ""

# weather
var weathers: Array = []
var wA: Dictionary
var wB: Dictionary
var w_blend := 1.0
var w_timer := 0.0
var w_transition := false
var wdiff := 1.0          # weather difficulty multiplier (speed + spawn rate)

# input
var touch_start := Vector2.ZERO

# UI
var ui_score: Label
var ui_coins: Label
var ui_info: Label
var ui_center: Label
var ui_sub: Label
var ui_caption: Label
var ui_tut: Label


func _ready() -> void:
	randomize()
	_load_high()
	_build_weathers()
	_build_environment()
	_build_camera()
	_load_assets()
	_build_ground()
	_build_player()
	_build_streetlights()
	_build_storm()
	_build_effects()
	_build_weather_particles()
	_build_audio()
	_build_ui()
	_randomize_weather()
	_setup_quality()
	demo_caption = "Watch the demo…"
	if "--shots" in OS.get_cmdline_args():
		_run_shots()

func _setup_quality() -> void:
	# Anti-aliasing for clean edges (mobile-friendly) + a touch of sharpening.
	var vp := get_viewport()
	vp.msaa_3d = Viewport.MSAA_4X
	vp.screen_space_aa = Viewport.SCREEN_SPACE_AA_FXAA
	vp.use_debanding = true
	vp.scaling_3d_mode = Viewport.SCALING_3D_MODE_BILINEAR
	vp.scaling_3d_scale = 1.0

func _run_shots() -> void:
	# Debug: capture a few frames to res://shots/ then quit (for development).
	var d := DirAccess.open("res://")
	if d != null and not d.dir_exists("shots"):
		d.make_dir("shots")
	# Force dramatic weather so the harness can verify storm/tornado visuals.
	if "--tornado" in OS.get_cmdline_args():
		for w in weathers:
			if w["name"] == "Tornado":
				wA = w; wB = w; w_blend = 1.0; w_transition = false; _apply_weather()
	for arg in ["--forest", "--beach", "--desert"]:
		if arg in OS.get_cmdline_args():
			biome_idx = ["--forest", "--beach", "--desert"].find(arg) + 1
			biome_timer = 999.0
			_apply_biome()
	if "--jet" in OS.get_cmdline_args():
		jet_t = 999.0
		state = St.PLAYING   # leave the demo so the flying pose is visible
	await get_tree().create_timer(1.0).timeout
	for i in range(8):
		await get_tree().create_timer(0.8).timeout
		await RenderingServer.frame_post_draw
		var img := get_viewport().get_texture().get_image()
		if img != null:
			img.save_png("res://shots/shot_%d.png" % i)
	get_tree().quit()


# ==============================================================================
#  Build
# ==============================================================================
func _mat(col: Color, emit_e := 0.0, metallic := 0.0, rough := 0.85) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_color = col
	m.metallic = metallic
	m.roughness = rough
	if emit_e > 0.0:
		m.emission_enabled = true
		m.emission = col
		m.emission_energy_multiplier = emit_e
	return m

func _flat(col: Color) -> StandardMaterial3D:
	# Unlit flat colour to match the kit's KHR_materials_unlit look.
	var m := StandardMaterial3D.new()
	m.albedo_color = col
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	return m

func _pbr_mat(base: String, tile: Vector2, ao := false) -> StandardMaterial3D:
	# Realistic CC0 material (ambientCG): colour + roughness + normal (+ AO).
	var m := StandardMaterial3D.new()
	m.albedo_texture = load("res://assets/pbr/%s_color.jpg" % base)
	var r = load("res://assets/pbr/%s_rough.jpg" % base)
	if r != null:
		m.roughness_texture = r
		m.roughness = 1.0
	var nrm = load("res://assets/pbr/%s_normal.jpg" % base)
	if nrm != null:
		m.normal_enabled = true
		m.normal_texture = nrm
		m.normal_scale = 1.0
	if ao:
		var a = load("res://assets/pbr/%s_ao.jpg" % base)
		if a != null:
			m.ao_enabled = true
			m.ao_texture = a
			m.ao_light_affect = 0.5
	m.uv1_scale = Vector3(tile.x, tile.y, 1.0)
	return m

func _box(size: Vector3, col: Color, parent: Node3D, pos := Vector3.ZERO, emit := 0.0) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	mi.mesh = bm
	mi.material_override = _mat(col, emit)
	mi.position = pos
	parent.add_child(mi)
	return mi

func _cyl(rad: float, h: float, col: Color, parent: Node3D, pos := Vector3.ZERO) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = rad
	cm.bottom_radius = rad
	cm.height = h
	mi.mesh = cm
	mi.material_override = _mat(col, 0.0, 0.2, 0.5)
	mi.position = pos
	parent.add_child(mi)
	return mi

func _cap(rad: float, h: float, col: Color, parent: Node3D, pos := Vector3.ZERO) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var cm := CapsuleMesh.new()
	cm.radius = rad
	cm.height = h
	mi.mesh = cm
	mi.material_override = _mat(col)
	mi.position = pos
	parent.add_child(mi)
	return mi

func _sph(rad: float, col: Color, parent: Node3D, pos := Vector3.ZERO) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var sm := SphereMesh.new()
	sm.radius = rad
	sm.height = rad * 2.0
	mi.mesh = sm
	mi.material_override = _mat(col)
	mi.position = pos
	parent.add_child(mi)
	return mi

func _prism(size: Vector3, col: Color, parent: Node3D, pos := Vector3.ZERO) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var pm := PrismMesh.new()
	pm.size = size
	mi.mesh = pm
	mi.material_override = _mat(col)
	mi.position = pos
	parent.add_child(mi)
	return mi

func _load_assets() -> void:
	runner_scene = load("res://assets/char/jogging7.fbx")
	if runner_scene == null:
		runner_scene = load("res://assets/char/runner.glb")
	for n in ["sedan", "suv", "taxi", "van", "police", "hatchback-sports"]:
		var s = load("res://assets/car/car-%s.glb" % n)
		if s != null:
			car_scenes.append(s)
	cone_scene = load("res://assets/car/cone.glb")
	for c in ["a", "b", "c", "d", "e", "f", "g", "h"]:
		var s = load("res://assets/city/building-%s.glb" % c)
		if s != null:
			building_scenes.append(s)
	for n in ["tree_default", "tree_pineDefaultA"]:
		var s = load("res://assets/nature/%s.glb" % n)
		if s != null: tree_scenes.append(s)
	for n in ["tree_palm", "tree_palmShort"]:
		var s = load("res://assets/nature/%s.glb" % n)
		if s != null: palm_scenes.append(s)
	for n in ["cactus_tall", "cactus_short", "rock_tallE"]:   # narrow props only
		var s = load("res://assets/nature/%s.glb" % n)
		if s != null: desert_scenes.append(s)
	for n in ["grass", "grass_large", "flower_redA", "flower_yellowA", "rock_smallA", "rock_smallC", "plant_bushDetailed", "mushroom_red", "cactus_short"]:
		var s = load("res://assets/nature/%s.glb" % n)
		if s != null: scatter_lib[n] = s
	_build_biomes()

func _scat(names: Array) -> Array:
	var a := []
	for n in names:
		if scatter_lib.has(n):
			a.append(scatter_lib[n])
	return a

func _build_biomes() -> void:
	biomes = [
		{"name": "City", "props": building_scenes, "ground": "concrete", "h": Vector2(6, 10), "off": Vector2(13, 20),
		 "scatter": _scat(["plant_bushDetailed", "rock_smallA", "grass"])},
		{"name": "Forest", "props": tree_scenes, "ground": "grass", "h": Vector2(5, 9), "off": Vector2(6, 12),
		 "scatter": _scat(["grass", "grass_large", "flower_redA", "flower_yellowA", "plant_bushDetailed", "mushroom_red", "rock_smallA"])},
		{"name": "Beach", "props": palm_scenes, "ground": "sand", "h": Vector2(5, 8), "off": Vector2(6, 11),
		 "scatter": _scat(["grass", "rock_smallA", "rock_smallC"])},
		{"name": "Desert", "props": desert_scenes, "ground": "sand", "h": Vector2(3, 5), "off": Vector2(9, 16),
		 "scatter": _scat(["rock_smallA", "rock_smallC", "cactus_short"])},
	]

## Combined AABB of every VisualInstance3D under [root], expressed in [ref] space.
func _merged_aabb(ref: Node3D, root: Node3D) -> AABB:
	var result := AABB()
	var started := false
	for vi in root.find_children("*", "VisualInstance3D", true, false):
		var v := vi as VisualInstance3D
		var rel: Transform3D = ref.global_transform.affine_inverse() * v.global_transform
		var a: AABB = rel * v.get_aabb()
		if not started:
			result = a
			started = true
		else:
			result = result.merge(a)
	return result

## Instance [scene], auto-scale it to [target] along [fit_axis] ("x" or "y"),
## centre it on X/Z and (optionally) sit it on the ground. Returns a wrapper.
func _add_model(scene: PackedScene, parent: Node3D, target: float, fit_axis: String, ground := true) -> Node3D:
	var wrap := Node3D.new()
	parent.add_child(wrap)
	if scene == null:
		return wrap
	var inst := scene.instantiate()
	wrap.add_child(inst)
	var box := _merged_aabb(wrap, inst as Node3D)
	var size := box.size
	if size.y < 0.0001 and size.x < 0.0001:
		return wrap
	var s: float = (target / max(size.x, 0.001)) if fit_axis == "x" else (target / max(size.y, 0.001))
	(inst as Node3D).scale = Vector3(s, s, s)
	var oy: float = -box.position.y * s if ground else -(box.position.y + size.y * 0.5) * s
	(inst as Node3D).position = Vector3(-(box.position.x + size.x * 0.5) * s, oy, -(box.position.z + size.z * 0.5) * s)
	return wrap

func _audio(path: String, db: float) -> AudioStreamPlayer:
	var p := AudioStreamPlayer.new()
	p.stream = load(path)
	p.volume_db = db
	add_child(p)
	return p

func _build_audio() -> void:
	sfx_coin = _audio("res://assets/sfx/coin.ogg", -5.0)
	sfx_jump = _audio("res://assets/sfx/jump.ogg", -9.0)
	sfx_slide = _audio("res://assets/sfx/slide.ogg", -9.0)
	sfx_crash = _audio("res://assets/sfx/crash.ogg", -1.0)
	sfx_thunder = _audio("res://assets/sfx/crash.ogg", -1.0)
	sfx_thunder.pitch_scale = 0.45   # lower/longer -> thunder rumble
	sfx_powerup = _audio("res://assets/sfx/powerup.ogg", -4.0)
	sfx_gameover = _audio("res://assets/sfx/gameover.ogg", -3.0)
	# Seamless ambient background-music loop (original CC0 composition).
	music = _audio("res://assets/sfx/music_loop.wav", -16.0)
	if music.stream != null:
		music.play()

func _play(p: AudioStreamPlayer) -> void:
	if p != null and p.stream != null:
		p.play()

func _build_streetlights() -> void:
	for i in 8:
		var n := Node3D.new()
		add_child(n)
		_cyl(0.08, 5.0, Color(0.2, 0.2, 0.23), n, Vector3(0, 2.5, 0))            # pole
		_box(Vector3(0.8, 0.12, 0.16), Color(0.22, 0.22, 0.25), n, Vector3(0.38, 4.95, 0))  # arm
		var lamp_mat := _flat(Color(1.0, 0.9, 0.65))
		var lamp := _box(Vector3(0.38, 0.16, 0.28), Color(1.0, 0.9, 0.65), n, Vector3(0.66, 4.88, 0))
		lamp.material_override = lamp_mat
		n.position = Vector3(0, 0, 200)
		streetlights.append({"node": n, "mat": lamp_mat, "active": false})

func _update_props(dt: float) -> void:
	var nf := _night_factor()
	for s in streetlights:
		if s["active"]:
			s["node"].position.z += _ws() * dt
			if s["node"].position.z > 10.0:
				s["active"] = false
				s["node"].position = Vector3(0, 0, 200)
		var m: StandardMaterial3D = s["mat"]
		m.emission_enabled = nf > 0.15
		m.emission = Color(1.0, 0.86, 0.55)
		m.emission_energy_multiplier = nf * 4.0
	light_timer -= _ws() * dt
	if light_timer <= 0.0:
		light_timer += 18.0
		for side in [-1.0, 1.0]:
			for s in streetlights:
				if not s["active"]:
					s["active"] = true
					s["node"].position = Vector3(side * (LANE_X + 1.6), 0, SPAWN_Z)
					s["node"].rotation_degrees = Vector3(0, 0.0 if side < 0 else 180.0, 0)
					break

func _build_storm() -> void:
	# Lightning bolt streak (hidden until a strike).
	bolt = Node3D.new()
	add_child(bolt)
	var seg := _box(Vector3(0.5, 60, 0.5), Color(0.9, 0.95, 1.0), bolt, Vector3(0, 30, 0))
	var bm := _flat(Color(0.9, 0.95, 1.0))
	bm.emission_enabled = true
	bm.emission = Color(0.85, 0.92, 1.0)
	bm.emission_energy_multiplier = 8.0
	seg.material_override = bm
	bolt.visible = false

	# Tornado funnel: stacked tapering cylinders (wider at the top).
	tornado = Node3D.new()
	add_child(tornado)
	var y := 0.0
	var r := 0.6
	for i in 8:
		var shade := 0.22 - i * 0.008
		_cyl(r, 2.6, Color(shade, shade - 0.02, shade - 0.06), tornado, Vector3(0, y + 1.3, 0))
		y += 2.4
		r += 0.6
	tornado.visible = false

func _wfac(n: String) -> float:
	var a: float = (1.0 - w_blend) if wA["name"] == n else 0.0
	var b: float = w_blend if wB["name"] == n else 0.0
	return a + b

func _update_storm(dt: float) -> void:
	var storm_f := _wfac("Thunderstorm")
	var tor_f := _wfac("Tornado")
	var active := storm_f > 0.2 or tor_f > 0.2

	if active:
		lightning_timer -= dt
		if lightning_timer <= 0.0:
			lightning_timer = randf_range(2.2, 6.0)
			lightning_flash = 1.0
			_play(sfx_thunder)
			bolt.position = Vector3(randf_range(-20, 20), 0, -randf_range(40, 75))
			bolt.rotation_degrees = Vector3(0, 0, randf_range(-12, 12))
			bolt.visible = true
			bolt_timer = 0.14
	if bolt_timer > 0.0:
		bolt_timer -= dt
		if bolt_timer <= 0.0:
			bolt.visible = false
	lightning_flash = max(0.0, lightning_flash - dt * 3.0)
	if flash_rect != null:
		flash_rect.color.a = lightning_flash * 0.6

	tornado.visible = tor_f > 0.05
	if tornado.visible:
		tornado.rotation.y += dt * 6.0
		tornado.scale = Vector3(tor_f, 1.0, tor_f)
		tornado.position = Vector3(-15 + sin(ui_time * 0.4) * 5.0, 0, -52)

func _build_effects() -> void:
	# Beach tide: a translucent water sheet that washes over the road and back.
	water_plane = Node3D.new()
	add_child(water_plane)
	var wp := MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = Vector2(24, 260)
	wp.mesh = pm
	var wm := StandardMaterial3D.new()
	wm.albedo_color = Color(0.18, 0.46, 0.72, 0.6)
	wm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	wm.metallic = 0.2
	wm.roughness = 0.04          # glossy, reflects the sky
	wm.rim_enabled = true
	wm.rim = 0.6
	wm.emission_enabled = true
	wm.emission = Color(0.1, 0.28, 0.46)
	wm.emission_energy_multiplier = 0.2
	wp.material_override = wm
	wp.position = Vector3(-18, 0.06, -90)
	water_plane.add_child(wp)
	water_plane.visible = false

	# Tornado flying "trees" (trunk + foliage) tumbling across the road.
	for i in 12:
		var dn := Node3D.new()
		add_child(dn)
		_box(Vector3(0.3, 1.1, 0.3), Color(0.4, 0.28, 0.15), dn, Vector3(0, 0.55, 0))
		_box(Vector3(1.1, 1.1, 1.1), Color(0.2, 0.5, 0.2), dn, Vector3(0, 1.4, 0))
		dn.visible = false
		debris.append({"node": dn, "vel": Vector3.ZERO, "spin": Vector3.ZERO})

	# Desert blowing sand (horizontal streaks in front of the camera).
	sand_root = Node3D.new()
	cam.add_child(sand_root)
	for i in 40:
		var s := _box(Vector3(0.18, 0.04, 0.04), Color(0.85, 0.72, 0.45, 1), sand_root)
		s.position = Vector3(randf_range(-9, 9), randf_range(-3, 4), -randf_range(3, 12))
		sand_bits.append(s)
	sand_root.visible = false

	# Running foot-dust puffs.
	for i in 10:
		var dn := MeshInstance3D.new()
		add_child(dn)
		var sm := SphereMesh.new(); sm.radius = 0.16; sm.height = 0.32
		dn.mesh = sm
		var dm := StandardMaterial3D.new()
		dm.albedo_color = Color(0.7, 0.66, 0.55, 0.0)
		dm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		dm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		dn.material_override = dm
		dn.visible = false
		dust.append({"node": dn, "vel": Vector3.ZERO, "life": 0.0})

	# Spark/debris burst pool for destroyed obstacles.
	for i in 24:
		var sk := MeshInstance3D.new()
		add_child(sk)
		var bm := BoxMesh.new(); bm.size = Vector3(0.13, 0.13, 0.13)
		sk.mesh = bm
		var skm := StandardMaterial3D.new()
		skm.albedo_color = Color(1.0, 0.7, 0.2)
		skm.emission_enabled = true
		skm.emission = Color(1.0, 0.55, 0.1)
		skm.emission_energy_multiplier = 3.5
		sk.material_override = skm
		sk.visible = false
		sparks.append({"node": sk, "vel": Vector3.ZERO, "life": 0.0})

func _spark_burst(pos: Vector3) -> void:
	var n := 0
	for s in sparks:
		if not s["node"].visible:
			s["node"].visible = true
			s["node"].position = pos
			s["node"].scale = Vector3.ONE
			s["vel"] = Vector3(randf_range(-4, 4), randf_range(2, 6), randf_range(-2, 5))
			s["life"] = 0.5
			n += 1
			if n >= 9:
				break

func _landing_puff() -> void:
	var n := 0
	for d in dust:
		if not d["node"].visible:
			d["node"].visible = true
			d["node"].position = Vector3(p_x + randf_range(-0.35, 0.35), 0.1, 0.3)
			d["node"].scale = Vector3.ONE * 0.8
			d["vel"] = Vector3(randf_range(-1, 1), randf_range(0.4, 1.0), randf_range(1, 3))
			d["life"] = 0.45
			(d["node"].material_override as StandardMaterial3D).albedo_color = Color(0.7, 0.66, 0.55, 0.6)
			n += 1
			if n >= 4:
				break

func _update_biome(dt: float) -> void:
	biome_timer -= dt
	if biome_timer <= 0.0:
		biome_timer = randf_range(28, 42)
		biome_idx = (biome_idx + 1) % biomes.size()
		_apply_biome()

func _update_effects(dt: float) -> void:
	# Beach tide washes in and out over the road.
	if water_plane != null and water_plane.visible:
		var wp := water_plane.get_child(0) as MeshInstance3D
		wp.position.x = -20.0 + (sin(ui_time * 0.5) * 0.5 + 0.5) * 22.0
	# Tornado flying trees.
	var tor := _wfac("Tornado")
	if tor > 0.3:
		debris_timer -= dt
		if debris_timer <= 0.0:
			debris_timer = randf_range(0.4, 1.1)
			for d in debris:
				if not d["node"].visible:
					var sidez := -1.0 if randf() < 0.5 else 1.0
					d["node"].visible = true
					d["node"].position = Vector3(sidez * 14.0, randf_range(1, 5), SPAWN_Z * 0.55)
					d["vel"] = Vector3(-sidez * randf_range(6, 12), randf_range(2, 5), randf_range(9, 17))
					d["spin"] = Vector3(randf_range(-4, 4), randf_range(-4, 4), randf_range(-4, 4))
					break
	for d in debris:
		if d["node"].visible:
			d["node"].position += d["vel"] * dt
			d["vel"].y -= 7.0 * dt
			d["node"].rotation += d["spin"] * dt
			if d["node"].position.z > CULL_Z or d["node"].position.y < -3.0:
				d["node"].visible = false
	# Desert blowing sand.
	var desert: bool = biomes[biome_idx]["name"] == "Desert"
	sand_root.visible = desert or tor > 0.3
	if sand_root.visible:
		for s in sand_bits:
			s.position.x -= (11.0 + speed) * dt
			if s.position.x < -10.0:
				s.position = Vector3(10.0, randf_range(-3, 4), -randf_range(3, 12))

	# Foot dust kicked up while running on the ground.
	if state != St.OVER and p_y < 0.2 and tank_t <= 0.0 and jet_t <= 0.0:
		dust_timer -= dt
		if dust_timer <= 0.0:
			dust_timer = 0.05
			var dcol := Color(0.72, 0.66, 0.55)
			match biomes[biome_idx]["name"]:
				"City": dcol = Color(0.6, 0.6, 0.62)
				"Forest": dcol = Color(0.5, 0.58, 0.42)
			for d in dust:
				if not d["node"].visible:
					d["node"].visible = true
					d["node"].position = Vector3(p_x + randf_range(-0.25, 0.25), 0.12, 0.3)
					d["node"].scale = Vector3.ONE * 0.6
					d["vel"] = Vector3(randf_range(-0.4, 0.4), randf_range(0.4, 0.9), randf_range(2.5, 4.5))
					d["life"] = 0.5
					(d["node"].material_override as StandardMaterial3D).albedo_color = Color(dcol.r, dcol.g, dcol.b, 0.6)
					break
	for d in dust:
		if d["node"].visible:
			d["life"] -= dt
			d["node"].position += d["vel"] * dt
			d["node"].scale *= (1.0 + dt * 1.8)
			var dm := d["node"].material_override as StandardMaterial3D
			dm.albedo_color.a = clamp(d["life"] / 0.5, 0.0, 1.0) * 0.6
			if d["life"] <= 0.0:
				d["node"].visible = false

	# Destroy sparks.
	for s in sparks:
		if s["node"].visible:
			s["life"] -= dt
			s["node"].position += s["vel"] * dt
			s["vel"].y -= 13.0 * dt
			s["node"].scale = Vector3.ONE * clamp(s["life"] / 0.5, 0.1, 1.0)
			if s["life"] <= 0.0:
				s["node"].visible = false

func _build_environment() -> void:
	var we := WorldEnvironment.new()
	env = Environment.new()
	env.background_mode = Environment.BG_SKY
	var sky := Sky.new()
	# Realistic CC0 HDRI sky (Poly Haven) for image-based lighting + reflections.
	sky_mat = PanoramaSkyMaterial.new()
	sky_mat.panorama = load("res://assets/env/sky.hdr")
	sky.sky_material = sky_mat
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 1.0
	env.tonemap_mode = Environment.TONE_MAPPER_ACES
	env.tonemap_exposure = 0.88
	env.tonemap_white = 1.0
	# Very restrained bloom — only genuinely bright things (coins, lamps) glow.
	# Pushed the threshold up and the intensity down so the scene reads crisp
	# instead of hazy.
	env.glow_enabled = true
	env.glow_intensity = 0.2
	env.glow_strength = 0.85
	env.glow_bloom = 0.0
	env.glow_blend_mode = Environment.GLOW_BLEND_MODE_ADDITIVE
	env.glow_hdr_threshold = 1.8
	# Gentle colour grading: a touch more contrast clears the milky look.
	env.adjustment_enabled = true
	env.adjustment_brightness = 1.0
	env.adjustment_contrast = 1.16
	env.adjustment_saturation = 1.2
	we.environment = env
	add_child(we)

	sun = DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-52, -38, 0)
	sun.light_energy = 1.4
	sun.light_color = Color(1.0, 0.96, 0.88)
	sun.shadow_enabled = true
	sun.shadow_blur = 1.0
	sun.directional_shadow_max_distance = 48.0   # crisp shadows only near camera
	sun.shadow_normal_bias = 1.5
	sun.light_angular_distance = 0.6             # soft, sun-sized shadow penumbra
	sun.light_specular = 0.6
	add_child(sun)

func _build_camera() -> void:
	cam = Camera3D.new()
	cam.fov = 74.0
	cam.position = Vector3(0, 4.4, 7.8)
	# Wide, high vantage looking far down the street: open, not claustrophobic,
	# and you can read the next obstacle well in advance.
	cam.look_at_from_position(cam.position, Vector3(0, 1.0, -28), Vector3.UP)
	add_child(cam)

func _build_ground() -> void:
	# Wide surrounding ground (swapped per biome).
	surround_mi = MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = Vector2(80, 260)
	surround_mi.mesh = pm
	surround_mi.material_override = _pbr_mat("concrete", Vector2(20, 90))
	surround_mi.position = Vector3(0, -0.02, -90)
	add_child(surround_mi)

	# Asphalt road with a realistic PBR material.
	var road := MeshInstance3D.new()
	var rpm := PlaneMesh.new()
	rpm.size = Vector2(LANE_X * 2.0 + 2.2, 260)
	road.mesh = rpm
	road.material_override = _pbr_mat("asphalt", Vector2(2, 90), true)
	road.position = Vector3(0, 0.0, -90)
	add_child(road)

	# Raised concrete sidewalks lining the road.
	var walk_mat := _pbr_mat("concrete", Vector2(3, 120))
	var road_half := LANE_X + 1.1
	for side in [-1.0, 1.0]:
		var sw := MeshInstance3D.new()
		var sm := BoxMesh.new()
		sm.size = Vector3(2.4, 0.16, 260)
		sw.mesh = sm
		sw.material_override = walk_mat
		sw.position = Vector3(side * (road_half + 1.2), 0.08, -90)
		add_child(sw)

	# Centre dashed line (pool, recycled forward).
	var n := 26
	for i in n:
		var d := _box(Vector3(0.18, 0.02, 2.2), Color(0.92, 0.78, 0.25, 1), self)
		d.position = Vector3(0, 0.02, SPAWN_Z + i * (GAP_Z * 0.0 + 4.0))
		dashes.append(d)

	# Pool of side-prop containers (filled with the current biome's prop on spawn).
	for i in 11:
		var node := Node3D.new()
		add_child(node)
		node.position = Vector3(0, 0, 200)
		buildings.append({"node": node, "active": false})

	# Dense scatter pool: small ground props (grass/flowers/rocks) near the road.
	for i in 28:
		var node := Node3D.new()
		add_child(node)
		node.position = Vector3(0, 0, 200)
		scatter_pool.append({"node": node, "active": false})

func _populate_prop(node: Node3D) -> void:
	# Fill a side-prop container with a model from the current biome.
	for c in node.get_children():
		c.queue_free()
	var b: Dictionary = biomes[biome_idx]
	var props: Array = b["props"]
	var h: Vector2 = b["h"]
	if props.is_empty():
		_box(Vector3(4, 8, 4), Color(0.42, 0.45, 0.5), node, Vector3(0, 4, 0))
		return
	var scene: PackedScene = props[randi() % props.size()]
	_add_model(scene, node, randf_range(h.x, h.y), "y", true)

func _populate_scatter(node: Node3D) -> void:
	for c in node.get_children():
		c.queue_free()
	var props: Array = biomes[biome_idx]["scatter"]
	if props.is_empty():
		return
	var scene: PackedScene = props[randi() % props.size()]
	_add_model(scene, node, randf_range(0.4, 1.3), "y", true)

func _apply_biome() -> void:
	if surround_mi != null:
		surround_mi.material_override = _pbr_mat(biomes[biome_idx]["ground"], Vector2(20, 90))
	if water_plane != null:
		water_plane.visible = biomes[biome_idx]["name"] == "Beach"

func _build_player() -> void:
	player = Node3D.new()
	add_child(player)

	# Player character (Mixamo jogging FBX), running on foot.
	runner_model = _add_model(runner_scene, player, 1.2, "y", true)
	runner_model.rotation_degrees = Vector3(0, 180, 0)   # face away from the camera

	# Tint the mannequin into a red-hoodie stand-in (shaded so it catches light).
	var surf := runner_model.find_child("Beta_Surface", true, false)
	if surf is MeshInstance3D:
		(surf as MeshInstance3D).material_override = _mat(Color(0.82, 0.15, 0.15), 0.0, 0.0, 0.7)
	var joints := runner_model.find_child("Beta_Joints", true, false)
	if joints is MeshInstance3D:
		(joints as MeshInstance3D).material_override = _mat(Color(0.16, 0.17, 0.2), 0.0, 0.1, 0.6)

	var ap := runner_model.find_child("AnimationPlayer", true, false)
	var skel := runner_model.find_child("Skeleton3D", true, false)
	if ap != null:
		runner_anim = ap as AnimationPlayer
		# Ensure the animation's bone tracks resolve to THIS skeleton. Some FBX
		# rigs nest the skeleton deeper than the track paths assume, which leaves
		# the character frozen in its bind pose (legs not moving).
		if skel != null and skel.get_parent() != null:
			runner_anim.root_node = runner_anim.get_path_to(skel.get_parent())
		var list := runner_anim.get_animation_list()
		var chosen := ""
		# Prefer a real locomotion clip (Mixamo names its clip "mixamo_com").
		for a in list:
			var an := String(a).to_lower()
			if an.contains("jog") or an.contains("run") or an.contains("sprint") or an.contains("mixamo"):
				chosen = a
				break
		# Otherwise take any clip that isn't the FBX's empty default "Take 001".
		if chosen == "":
			for a in list:
				if String(a).to_lower() != "take 001":
					chosen = a
					break
		if chosen == "" and list.size() > 0:
			chosen = list[0]
		if chosen != "":
			var anim := runner_anim.get_animation(chosen)
			anim.loop_mode = Animation.LOOP_LINEAR
			# Lock root motion in place (non-destructive): route the hips
			# position track through root motion so the legs still animate.
			for ti in range(anim.get_track_count()):
				if anim.track_get_type(ti) == Animation.TYPE_POSITION_3D \
						and String(anim.track_get_path(ti)).to_lower().contains("hips"):
					runner_anim.root_motion_track = anim.track_get_path(ti)
					break
			jog_clip = chosen
			# Graft the baked Flying clip (same rig) for jetpack mode.
			_add_flying_clip()
			runner_anim.play(chosen)

	# Shield bubble (shown only while a shield power-up is active).
	shield_bubble = Node3D.new()
	player.add_child(shield_bubble)
	var sb := _sph(0.95, Color(0.4, 0.7, 1.0), shield_bubble, Vector3(0, 0.95, 0))
	var sbm := StandardMaterial3D.new()
	sbm.albedo_color = Color(0.45, 0.72, 1.0, 0.22)
	sbm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	sbm.emission_enabled = true
	sbm.emission = Color(0.45, 0.72, 1.0)
	sbm.emission_energy_multiplier = 0.7
	sb.material_override = sbm
	shield_bubble.visible = false

	# Tank ride (shown while the tank power-up is active; runner is hidden).
	tank_model = Node3D.new()
	player.add_child(tank_model)
	_box(Vector3(1.5, 0.5, 2.3), Color(0.42, 0.46, 0.34), tank_model, Vector3(0, 0.5, 0))
	_box(Vector3(0.38, 0.5, 2.5), Color(0.14, 0.14, 0.14), tank_model, Vector3(-0.74, 0.32, 0))
	_box(Vector3(0.38, 0.5, 2.5), Color(0.14, 0.14, 0.14), tank_model, Vector3(0.74, 0.32, 0))
	_box(Vector3(0.95, 0.5, 0.95), Color(0.36, 0.4, 0.3), tank_model, Vector3(0, 0.95, 0))
	_box(Vector3(0.2, 0.2, 1.5), Color(0.3, 0.32, 0.26), tank_model, Vector3(0, 1.0, -1.0))
	tank_model.visible = false

	# Bazooka (shown while the gun power-up is active).
	gun_model = Node3D.new()
	player.add_child(gun_model)
	gun_model.position = Vector3(0.35, 1.0, -0.25)
	_box(Vector3(0.18, 0.18, 1.1), Color(0.22, 0.22, 0.26), gun_model, Vector3(0, 0, -0.45))
	_box(Vector3(0.26, 0.26, 0.3), Color(0.45, 0.16, 0.12), gun_model, Vector3(0, 0, 0.12))
	gun_model.visible = false

	# Jetpack flame (under the player, shown while flying): a tapered cone that
	# narrows to a point, so it reads as a soft flame rather than a hard box.
	jet_flame = Node3D.new()
	player.add_child(jet_flame)
	jet_flame.position = Vector3(0, 0.2, 0.15)
	var fl := MeshInstance3D.new()
	var fc := CylinderMesh.new()
	fc.top_radius = 0.16        # wide at the nozzle
	fc.bottom_radius = 0.01     # tapers to a point below
	fc.height = 0.8
	fl.mesh = fc
	fl.position = Vector3(0, -0.45, 0)
	var flm := StandardMaterial3D.new()
	flm.albedo_color = Color(1, 0.55, 0.12, 0.9)
	flm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	flm.emission_enabled = true
	flm.emission = Color(1, 0.45, 0.08)
	flm.emission_energy_multiplier = 1.6
	fl.material_override = flm
	jet_flame.add_child(fl)
	jet_flame.visible = false

	player.position = Vector3(p_x, 0, 0)

func _add_flying_clip() -> void:
	# Add the baked flying clip (same Mixamo rig) to the runner's player so we
	# can switch to it during jetpack. Tiny .res instead of a redundant mesh FBX.
	if runner_anim == null:
		return
	var anim := load("res://assets/char/flying_anim.res") as Animation
	if anim == null:
		return
	anim = anim.duplicate()
	anim.loop_mode = Animation.LOOP_LINEAR
	var lib := runner_anim.get_animation_library("")
	if lib == null:
		lib = AnimationLibrary.new()
		runner_anim.add_animation_library("", lib)
	lib.add_animation("flying", anim)

func _build_weather_particles() -> void:
	rain_root = Node3D.new(); cam.add_child(rain_root)
	snow_root = Node3D.new(); cam.add_child(snow_root)
	for i in 55:
		var d := _box(Vector3(0.02, 0.5, 0.02), Color(0.8, 0.86, 0.95, 1), rain_root)
		d.position = Vector3(randf_range(-6, 6), randf_range(-5, 6), -randf_range(3, 12))
		rain_drops.append(d)
	for i in 45:
		var f := _box(Vector3(0.08, 0.08, 0.08), Color(1, 1, 1, 1), snow_root)
		f.position = Vector3(randf_range(-7, 7), randf_range(-5, 6), -randf_range(3, 12))
		snow_flakes.append(f)
	rain_root.visible = false
	snow_root.visible = false


# ==============================================================================
#  Weather
# ==============================================================================
func _build_weathers() -> void:
	weathers = [
		{"name": "Day", "top": Color(0.29,0.52,0.81), "hor": Color(0.81,0.86,0.88),
		 "sun": Color(1,0.96,0.84), "sun_e": 1.2, "amb": 1.0, "night": 0.0,
		 "fog": 0.0, "fog_c": Color(0.8,0.85,0.9), "precip": 0},
		{"name": "Sunset", "top": Color(0.23,0.16,0.38), "hor": Color(0.99,0.55,0.32),
		 "sun": Color(1,0.6,0.32), "sun_e": 1.1, "amb": 0.8, "night": 0.25,
		 "fog": 0.0025, "fog_c": Color(0.9,0.6,0.45), "precip": 0},
		{"name": "Night", "top": Color(0.03,0.05,0.13), "hor": Color(0.12,0.16,0.3),
		 "sun": Color(0.5,0.6,0.85), "sun_e": 0.28, "amb": 0.28, "night": 1.0,
		 "fog": 0.0, "fog_c": Color(0.1,0.12,0.2), "precip": 0, "diff": 1.06},
		{"name": "Dawn", "top": Color(0.34,0.42,0.66), "hor": Color(1,0.82,0.7),
		 "sun": Color(1,0.86,0.74), "sun_e": 0.9, "amb": 0.7, "night": 0.15,
		 "fog": 0.0035, "fog_c": Color(0.85,0.78,0.8), "precip": 0},
		{"name": "Rain", "top": Color(0.22,0.25,0.3), "hor": Color(0.45,0.5,0.55),
		 "sun": Color(0.7,0.74,0.8), "sun_e": 0.5, "amb": 0.5, "night": 0.4,
		 "fog": 0.005, "fog_c": Color(0.45,0.5,0.55), "precip": 1, "diff": 1.12},
		{"name": "Snow", "top": Color(0.58,0.66,0.77), "hor": Color(0.88,0.91,0.95),
		 "sun": Color(0.9,0.93,0.98), "sun_e": 0.8, "amb": 0.85, "night": 0.1,
		 "fog": 0.0045, "fog_c": Color(0.85,0.9,0.95), "precip": 2, "diff": 1.12},
		{"name": "Fog", "top": Color(0.59,0.6,0.62), "hor": Color(0.8,0.81,0.82),
		 "sun": Color(0.85,0.85,0.85), "sun_e": 0.6, "amb": 0.7, "night": 0.15,
		 "fog": 0.009, "fog_c": Color(0.8,0.81,0.82), "precip": 0},
		{"name": "Thunderstorm", "top": Color(0.1,0.11,0.14), "hor": Color(0.2,0.22,0.26),
		 "sun": Color(0.6,0.64,0.72), "sun_e": 0.3, "amb": 0.35, "night": 0.7,
		 "fog": 0.01, "fog_c": Color(0.2,0.22,0.27), "precip": 1, "diff": 1.28},
		{"name": "Tornado", "top": Color(0.16,0.15,0.1), "hor": Color(0.32,0.3,0.2),
		 "sun": Color(0.7,0.68,0.5), "sun_e": 0.4, "amb": 0.45, "night": 0.55,
		 "fog": 0.012, "fog_c": Color(0.32,0.3,0.22), "precip": 1, "diff": 1.42},
	]

func _randomize_weather() -> void:
	wA = weathers[randi() % weathers.size()]
	wB = wA
	w_blend = 1.0
	w_transition = false
	w_timer = randf_range(20, 32)
	_apply_weather()

func _update_weather(dt: float) -> void:
	if w_transition:
		w_blend += dt / WEATHER_FADE
		if w_blend >= 1.0:
			w_blend = 1.0
			wA = wB
			w_transition = false
			w_timer = randf_range(20, 32)
	else:
		w_timer -= dt
		if w_timer <= 0.0:
			var next: Dictionary = weathers[randi() % weathers.size()]
			if next["name"] == wA["name"]:
				next = weathers[(weathers.find(next) + 1) % weathers.size()]
			wB = next
			w_blend = 0.0
			w_transition = true
	_apply_weather()
	_update_precip(dt)

func _lf(a: float, b: float) -> float:
	return a + (b - a) * w_blend

func _apply_weather() -> void:
	var t := w_blend
	# HDRI sky stays; day/night comes from dimming the sky + sun + ambient.
	var nf: float = _night_factor()
	sky_mat.energy_multiplier = lerp(1.0, 0.12, nf)
	sun.light_color = wA["sun"].lerp(wB["sun"], t)
	sun.light_energy = _lf(wA["sun_e"], wB["sun_e"])
	env.ambient_light_energy = _lf(wA["amb"], wB["amb"]) * lerp(1.0, 0.4, nf)
	# Fog disabled entirely — it muddied the view and hurt readability.
	env.fog_enabled = false
	wdiff = lerp(float(wA.get("diff", 1.0)), float(wB.get("diff", 1.0)), t)

func _ws() -> float:
	# Effective world speed: base speed scaled by the current weather difficulty.
	return speed * wdiff

func _night_factor() -> float:
	return _lf(wA["night"], wB["night"])

func _rain_strength() -> float:
	var a := (1.0 - w_blend) if int(wA["precip"]) == 1 else 0.0
	var b := w_blend if int(wB["precip"]) == 1 else 0.0
	return a + b

func _snow_strength() -> float:
	var a := (1.0 - w_blend) if int(wA["precip"]) == 2 else 0.0
	var b := w_blend if int(wB["precip"]) == 2 else 0.0
	return a + b

func _update_precip(dt: float) -> void:
	var rs := _rain_strength()
	rain_root.visible = rs > 0.05
	if rain_root.visible:
		for d in rain_drops:
			d.position.y -= (28.0 + speed) * dt
			if d.position.y < -6:
				d.position = Vector3(randf_range(-6, 6), randf_range(4, 7), -randf_range(3, 12))
	var ss := _snow_strength()
	snow_root.visible = ss > 0.05
	if snow_root.visible:
		for f in snow_flakes:
			f.position.y -= 3.5 * dt
			f.position.x += sin(ui_time * 1.5 + f.position.z) * 0.6 * dt
			if f.position.y < -6:
				f.position = Vector3(randf_range(-7, 7), randf_range(4, 7), -randf_range(3, 12))


# ==============================================================================
#  Main loop
# ==============================================================================
func _process(dt: float) -> void:
	ui_time += dt
	run_cycle += dt * (6.0 + speed * 0.3)
	if crash_flash > 0.0:
		crash_flash = max(0.0, crash_flash - dt * 1.6)
	_update_weather(dt)
	_scroll_dashes(dt)
	_update_buildings(dt)
	_update_scatter(dt)
	_update_props(dt)
	_update_storm(dt)
	_update_biome(dt)
	_update_effects(dt)
	_animate_player(dt)
	_light_windows()

	match state:
		St.DEMO:
			_update_demo(dt)
		St.PLAYING:
			_update_playing(dt)
		_:
			pass

	_layout_ui()
	_refresh_ui()

func _update_playing(dt: float) -> void:
	game_time += dt
	speed = clamp(speed + ACCEL * dt, START_SPEED, MAX_SPEED)
	if shield_t > 0.0: shield_t = max(0.0, shield_t - dt)
	if magnet_t > 0.0: magnet_t = max(0.0, magnet_t - dt)
	if mult_t > 0.0: mult_t = max(0.0, mult_t - dt)
	if jet_t > 0.0: jet_t = max(0.0, jet_t - dt)
	if tank_t > 0.0: tank_t = max(0.0, tank_t - dt)
	if powerup_cd > 0.0: powerup_cd = max(0.0, powerup_cd - dt)
	if gun_t > 0.0:
		gun_t = max(0.0, gun_t - dt)
		_gun_fire(dt)
	_step_player(dt)
	dist_to_spawn -= _ws() * dt
	if dist_to_spawn <= 0.0:
		if game_time > GRACE:
			_spawn_row()
		dist_to_spawn += GAP_Z * randf_range(0.85, 1.45)
	_advance(dt)
	score += int(_ws() * dt * 6.0)

func _update_demo(dt: float) -> void:
	speed = 13.0
	_step_player(dt)
	demo_timer -= _ws() * dt
	if demo_timer <= 0.0:
		var lane := _round_lane()
		match demo_index % 3:
			0:
				_add_obstacle(0, "BARRIER"); _add_obstacle(1, "BARRIER")
			1:
				_add_obstacle(0, "OVERHANG"); _add_obstacle(1, "OVERHANG")
			_:
				_add_obstacle(lane, "CAR")
		_add_coin(randi() % 2)
		demo_index += 1
		demo_timer = 9.0
	_autopilot()
	_advance(dt)

func _autopilot() -> void:
	var nearest: Dictionary = {}
	var best := -INF
	for o in obstacles:
		if not o["resolved"] and o["node"].position.z < 0.0 and o["node"].position.z > best:
			best = o["node"].position.z
			nearest = o
	if nearest.is_empty():
		demo_caption = "Race the city — survive!"
		return
	if nearest["node"].position.z > -7.0:
		match nearest["type"]:
			"BARRIER":
				_do_jump(); demo_caption = "Swipe UP to JUMP barriers"
			"OVERHANG":
				_do_slide(); demo_caption = "Swipe DOWN to SLIDE under signs"
			"CAR":
				p_lane = 1 - int(nearest["lane"]); demo_caption = "Swipe LEFT / RIGHT to dodge cars"


# ==============================================================================
#  Spawning / world objects
# ==============================================================================
func _lane_x(lane: int) -> float:
	return -LANE_X if lane == 0 else LANE_X

func _round_lane() -> int:
	return 0 if p_x < 0.0 else 1

func _add_obstacle(lane: int, type: String) -> void:
	var n := Node3D.new()
	add_child(n)
	var lx := _lane_x(lane)
	match type:
		"BARRIER":
			# A row of real traffic cones to hurdle — JUMP it.
			if cone_scene != null:
				for cx in [-0.55, 0.0, 0.55]:
					var cm := _add_model(cone_scene, n, 0.9, "y", true)
					cm.position.x = cx
			else:
				_box(Vector3(1.74, 0.42, 0.16), Color(0.95, 0.45, 0.1), n, Vector3(0, 0.55, 0))
		"OVERHANG":
			# Sign gantry with a low caution bar to duck under — SLIDE under it.
			# Rounded (cylindrical) poles and crossbar read smoother than boxes.
			_cyl(0.1, 2.8, Color(0.5, 0.55, 0.6), n, Vector3(-0.96, 1.4, 0))
			_cyl(0.1, 2.8, Color(0.5, 0.55, 0.6), n, Vector3(0.96, 1.4, 0))
			var topbar := _cyl(0.15, 2.3, Color(0.45, 0.5, 0.55), n, Vector3(0, 2.65, 0))
			topbar.rotation_degrees = Vector3(0, 0, 90)
			_box(Vector3(1.9, 0.7, 0.1), Color(0.2, 0.5, 0.85), n, Vector3(0, 2.2, 0.16)).material_override = _mat(Color(0.2, 0.5, 0.85), 0.6)
			_box(Vector3(1.95, 0.45, 0.2), Color(0.9, 0.2, 0.2), n, Vector3(0, 1.35, 0))
			for sx in [-0.6, -0.2, 0.2, 0.6]:
				_box(Vector3(0.18, 0.47, 0.21), Color(0.96, 0.96, 0.96), n, Vector3(sx, 1.35, 0.01))
		"CAR":
			_make_car(n)
	n.position = Vector3(lx, 0, SPAWN_Z)
	obstacles.append({"node": n, "type": type, "lane": lane, "resolved": false})

func _make_car(parent: Node3D) -> void:
	if car_scenes.is_empty():
		_box(Vector3(1.8, 0.9, 3.4), Color(0.82, 0.2, 0.2), parent, Vector3(0, 0.6, 0))
		return
	var scene: PackedScene = car_scenes[randi() % car_scenes.size()]
	var m := _add_model(scene, parent, 1.9, "x", true)   # fit to ~lane width
	m.rotation_degrees = Vector3(0, 180, 0)              # rear toward the camera

func _add_coin(lane: int) -> void:
	var n := Node3D.new()
	add_child(n)
	var c := _cyl(0.45, 0.12, Color(0.98, 0.78, 0.2), n, Vector3.ZERO)
	c.rotation_degrees = Vector3(90, 0, 0)
	c.material_override = _mat(Color(0.98, 0.78, 0.2), 0.6)
	n.position = Vector3(_lane_x(lane), 1.6, SPAWN_Z)   # float higher, above barriers
	coin_nodes.append({"node": n, "lane": lane, "taken": false})

func _add_powerup(lane: int, type: String) -> void:
	var n := Node3D.new()
	add_child(n)
	var col := Color(0.45, 0.72, 1.0)      # shield = blue
	match type:
		"magnet": col = Color(1.0, 0.45, 0.2)  # orange
		"mult": col = Color(0.45, 1.0, 0.4)    # green
		"jet": col = Color(1.0, 0.9, 0.2)      # yellow
		"tank": col = Color(0.5, 0.55, 0.4)    # olive
		"gun": col = Color(0.9, 0.2, 0.15)     # red
	var core := _sph(0.42, col, n, Vector3.ZERO)
	core.material_override = _mat(col, 1.4)
	var ring := _cyl(0.6, 0.08, col, n, Vector3.ZERO)
	ring.rotation_degrees = Vector3(90, 0, 0)
	ring.material_override = _mat(col, 0.8)
	# Sit mid-gap between obstacle rows, so a power-up is never on or near a hazard.
	n.position = Vector3(_lane_x(lane), 1.5, SPAWN_Z - GAP_Z * 0.5)
	powerups.append({"node": n, "lane": lane, "type": type, "taken": false})

func _spawn_row() -> void:
	var roll := randf()
	if roll < 0.45:
		if randf() < 0.6:
			_add_obstacle(0, "BARRIER"); _add_obstacle(1, "BARRIER")
		else:
			_add_obstacle(randi() % 2, "BARRIER")
	elif roll < 0.8:
		if randf() < 0.6:
			_add_obstacle(0, "OVERHANG"); _add_obstacle(1, "OVERHANG")
		else:
			_add_obstacle(randi() % 2, "OVERHANG")
	else:
		_add_obstacle(randi() % 2, "CAR")
	# Lanes occupied by ANY obstacle this row.
	var occ := {}
	for o in obstacles:
		if o["node"].position.z == SPAWN_Z:
			occ[o["lane"]] = true
	var clear := []
	for l in [0, 1]:
		if not occ.has(l):
			clear.append(l)
	# Power-ups: rarer, with a cooldown so they never appear back-to-back.
	if powerup_cd <= 0.0 and randf() < 0.08:
		_add_powerup(randi() % 2, ["shield", "magnet", "mult", "jet", "tank", "gun"][randi() % 6])
		powerup_cd = 7.0
	elif randf() < 0.85:
		# Coins prefer a clear lane (can sit above a barrier you jump to grab).
		var cl: Array = clear if clear.size() > 0 else [0, 1]
		_add_coin(cl[randi() % cl.size()])

func _advance(dt: float) -> void:
	var move := _ws() * dt
	for o in obstacles:
		o["node"].position.z += move
		if not o["resolved"] and o["node"].position.z >= 0.0:
			o["resolved"] = true
			if state == St.PLAYING and int(o["lane"]) == _round_lane():
				if tank_t > 0.0:
					_spark_burst(o["node"].global_position + Vector3(0, 0.8, 0))
					o["node"].visible = false   # tank plows through
					_play(sfx_crash)
				elif _survives(o):
					pass
				elif shield_t > 0.0 or jet_t > 0.0:
					crash_flash = 0.4           # shield / jetpack shrugs it off
				else:
					_game_over()
	for c in coin_nodes:
		c["node"].position.z += move
		c["node"].rotate_y(dt * 4.0)
		# Magnet pulls coins toward the player's lane.
		if magnet_t > 0.0 and c["node"].position.z > -25.0:
			c["node"].position.x = lerp(c["node"].position.x, p_x, clamp(dt * 4.0, 0.0, 1.0))
		var grab: bool = int(c["lane"]) == _round_lane() or magnet_t > 0.0 or jet_t > 0.0
		if not c["taken"] and c["node"].position.z >= 0.0 and grab:
			c["taken"] = true
			c["node"].visible = false
			if state == St.PLAYING:
				coins += 1
				score += COIN_VALUE * (2 if mult_t > 0.0 else 1)
				_play(sfx_coin)
	for pu in powerups:
		pu["node"].position.z += move
		pu["node"].rotate_y(dt * 2.5)
		if not pu["taken"] and pu["node"].position.z >= 0.0 and int(pu["lane"]) == _round_lane():
			pu["taken"] = true
			pu["node"].visible = false
			if state == St.PLAYING:
				_activate_powerup(pu["type"])
	# cull
	var keep_o := []
	for o in obstacles:
		if o["node"].position.z > CULL_Z:
			o["node"].queue_free()
		else:
			keep_o.append(o)
	obstacles = keep_o
	var keep_c := []
	for c in coin_nodes:
		if c["node"].position.z > CULL_Z:
			c["node"].queue_free()
		else:
			keep_c.append(c)
	coin_nodes = keep_c
	var keep_pu := []
	for pu in powerups:
		if pu["node"].position.z > CULL_Z:
			pu["node"].queue_free()
		else:
			keep_pu.append(pu)
	powerups = keep_pu

func _activate_powerup(type: String) -> void:
	match type:
		"shield": shield_t = POWERUP_TIME
		"magnet": magnet_t = POWERUP_TIME
		"mult": mult_t = POWERUP_TIME
		"jet": jet_t = POWERUP_TIME
		"tank": tank_t = POWERUP_TIME
		"gun": gun_t = POWERUP_TIME; gun_timer = 0.0

func _gun_fire(dt: float) -> void:
	# Bazooka auto-targets and destroys the nearest obstacle ahead.
	gun_timer -= dt
	if gun_timer > 0.0:
		return
	gun_timer = 0.45
	var target: Dictionary = {}
	var best := -INF
	for o in obstacles:
		if not o["resolved"] and o["node"].position.z < 0.0 and o["node"].position.z > best:
			best = o["node"].position.z
			target = o
	if not target.is_empty():
		target["resolved"] = true
		_spark_burst(target["node"].global_position + Vector3(0, 0.8, 0))
		target["node"].visible = false
		_play(sfx_crash)
		if gun_model != null:
			gun_model.scale = Vector3(1.3, 1.3, 1.3)   # quick recoil pop

func _survives(o: Dictionary) -> bool:
	match o["type"]:
		"BARRIER":
			return p_y > JUMP_CLEAR
		"OVERHANG":
			return sliding
		_:
			return false


# ==============================================================================
#  Player movement / animation
# ==============================================================================
func _step_player(dt: float) -> void:
	var target_x := _lane_x(p_lane)
	# Critically-damped smoothing: eases in AND out for a buttery lane change
	# instead of the abrupt start a plain lerp gives.
	var smooth_time := 0.17
	var omega: float = 2.0 / smooth_time
	var xx: float = omega * dt
	var expf: float = 1.0 / (1.0 + xx + 0.48 * xx * xx + 0.235 * xx * xx * xx)
	var change: float = p_x - target_x
	var temp: float = (p_x_vel + omega * change) * dt
	p_x_vel = (p_x_vel - omega * temp) * expf
	p_x = target_x + (change + temp) * expf
	if jet_t > 0.0:
		# Jetpack: float above the obstacles.
		jumping = false; sliding = false
		p_y = lerp(p_y, 2.5, clamp(dt * 4.0, 0.0, 1.0))
	else:
		if jumping:
			p_y += p_vy * dt
			p_vy += GRAVITY * dt
			if p_y <= 0.0:
				p_y = 0.0; p_vy = 0.0; jumping = false
				_landing_puff()
		elif p_y > 0.0:
			p_y = lerp(p_y, 0.0, clamp(dt * 6.0, 0.0, 1.0))  # settle after jetpack ends
	if sliding:
		slide_t -= dt
		if slide_t <= 0.0:
			sliding = false
	player.position.x = p_x
	player.position.y = p_y

func _animate_player(dt: float) -> void:
	# Lean into turns; sync the run animation to speed; squash low when sliding.
	var lean: float = clamp((_lane_x(p_lane) - p_x) * 0.6, -0.5, 0.5)
	player.rotation.z = -lean
	_update_camera(dt)
	if runner_anim != null:
		# Swap to the flying clip during jetpack, otherwise jog.
		if runner_anim.has_animation("flying"):
			var want: String = "flying" if jet_t > 0.0 else jog_clip
			if want != "" and runner_anim.current_animation != want:
				runner_anim.play(want)
		runner_anim.speed_scale = 1.1 if jet_t > 0.0 else clamp(_ws() / 11.0, 0.7, 3.2)
	var in_tank := tank_t > 0.0
	if runner_model != null:
		runner_model.visible = not in_tank
		var target_sy: float = 0.5 if sliding else 1.0
		runner_model.scale.y = lerp(runner_model.scale.y, target_sy, clamp(dt * 14.0, 0.0, 1.0))
		# Lean forward into flight while the jetpack is active.
		var tilt: float = 40.0 if jet_t > 0.0 else 0.0
		var rx: float = lerp(runner_model.rotation_degrees.x, tilt, clamp(dt * 6.0, 0.0, 1.0))
		runner_model.rotation_degrees = Vector3(rx, 180.0, 0.0)
	if tank_model != null:
		tank_model.visible = in_tank
	if gun_model != null:
		gun_model.visible = gun_t > 0.0
		gun_model.scale = gun_model.scale.lerp(Vector3.ONE, clamp(dt * 8.0, 0.0, 1.0))
	if jet_flame != null:
		jet_flame.visible = jet_t > 0.0
		if jet_flame.visible:
			jet_flame.scale = Vector3(1.0, randf_range(0.7, 1.3), 1.0)   # flicker
	if shield_bubble != null:
		shield_bubble.visible = shield_t > 0.0 or jet_t > 0.0
		if shield_bubble.visible:
			shield_bubble.rotation.y += dt * 2.0

func _update_camera(dt: float) -> void:
	# Steady follow camera (no cinematic swing).
	cam.position.x = lerp(cam.position.x, p_x * 0.4, clamp(dt * 6.0, 0.0, 1.0))


# ==============================================================================
#  Scenery
# ==============================================================================
func _scroll_dashes(dt: float) -> void:
	var move := _ws() * dt
	for d in dashes:
		d.position.z += move
		if d.position.z > CULL_Z:
			d.position.z -= dashes.size() * 4.0

func _update_buildings(dt: float) -> void:
	var move := _ws() * dt
	for b in buildings:
		if b["active"]:
			b["node"].position.z += move
			if b["node"].position.z > 10.0:
				b["active"] = false
				b["node"].position = Vector3(0, 0, 200)
	build_timer -= _ws() * dt
	if build_timer <= 0.0:
		build_timer = randf_range(5.0, 9.0)
		for side in [-1.0, 1.0]:
			var slot = _free_building()
			if slot != null:
				slot["active"] = true
				_populate_prop(slot["node"])
				var orange: Vector2 = biomes[biome_idx]["off"]
				var off := randf_range(orange.x, orange.y)
				slot["node"].position = Vector3(side * (LANE_X + off), 0, SPAWN_Z - randf_range(0, 6))
				slot["node"].rotation_degrees = Vector3(0, randf_range(0, 360), 0)

func _update_scatter(dt: float) -> void:
	var move := _ws() * dt
	for s in scatter_pool:
		if s["active"]:
			s["node"].position.z += move
			if s["node"].position.z > 10.0:
				s["active"] = false
				s["node"].position = Vector3(0, 0, 200)
	scatter_timer -= _ws() * dt
	if scatter_timer <= 0.0:
		scatter_timer = randf_range(2.5, 5.0)
		for side in [-1.0, 1.0]:
			for s in scatter_pool:
				if not s["active"]:
					s["active"] = true
					_populate_scatter(s["node"])
					var off := randf_range(2.6, 6.0)   # close to the road
					s["node"].position = Vector3(side * (LANE_X + off), 0, SPAWN_Z - randf_range(0, 8))
					s["node"].rotation_degrees = Vector3(0, randf_range(0, 360), 0)
					break

func _free_building():
	for b in buildings:
		if not b["active"]:
			return b
	return null

func _light_windows() -> void:
	# Procedural-building fallback only; Kenney buildings carry baked window art.
	var nf := _night_factor()
	for b in buildings:
		if not b["node"].has_meta("win"):
			continue
		var win: MeshInstance3D = b["node"].get_meta("win")
		if win:
			var mat: StandardMaterial3D = win.material_override
			mat.emission_enabled = nf > 0.2
			mat.emission = Color(1.0, 0.86, 0.5)
			mat.emission_energy_multiplier = nf * 1.4


# ==============================================================================
#  Game flow
# ==============================================================================
func _start_game() -> void:
	for o in obstacles:
		o["node"].queue_free()
	for c in coin_nodes:
		c["node"].queue_free()
	for pu in powerups:
		pu["node"].queue_free()
	obstacles.clear()
	coin_nodes.clear()
	powerups.clear()
	shield_t = 0.0; magnet_t = 0.0; mult_t = 0.0; jet_t = 0.0; tank_t = 0.0; gun_t = 0.0
	highlight_t = 0.0
	p_lane = 0; p_x = -LANE_X; p_x_vel = 0.0; p_y = 0.0; p_vy = 0.0
	jumping = false; sliding = false
	speed = START_SPEED
	dist_to_spawn = GAP_Z
	game_time = 0.0; score = 0; coins = 0
	state = St.PLAYING

func _game_over() -> void:
	state = St.OVER
	crash_flash = 1.0
	_play(sfx_crash)
	_play(sfx_gameover)
	if score > high:
		high = score
		_save_high()

func _on_tap() -> void:
	if state == St.PLAYING:
		_do_jump()
	else:
		_start_game()

func _do_jump() -> void:
	if not jumping:
		jumping = true
		sliding = false
		p_vy = JUMP_VELOCITY
		_play(sfx_jump)

func _do_slide() -> void:
	if not jumping and not sliding:
		sliding = true
		slide_t = SLIDE_TIME
		_play(sfx_slide)

func move_left() -> void:
	if p_lane > 0:
		p_lane -= 1

func move_right() -> void:
	if p_lane < 1:
		p_lane += 1


# ==============================================================================
#  Input
# ==============================================================================
func _input(event: InputEvent) -> void:
	if event is InputEventScreenTouch:
		if event.pressed:
			touch_start = event.position
		else:
			_handle_swipe(event.position - touch_start)
	elif event is InputEventMouseButton:
		if event.pressed:
			touch_start = event.position
		else:
			_handle_swipe(event.position - touch_start)
	elif event is InputEventKey and event.pressed and not event.echo:
		match event.keycode:
			KEY_LEFT: move_left()
			KEY_RIGHT: move_right()
			KEY_UP: _do_jump()
			KEY_DOWN: _do_slide()
			KEY_SPACE, KEY_ENTER: _on_tap()

func _handle_swipe(delta: Vector2) -> void:
	if delta.length() < 40.0:
		_on_tap()
		return
	if state != St.PLAYING:
		_start_game()
		return
	if abs(delta.x) > abs(delta.y):
		if delta.x > 0: move_right()
		else: move_left()
	else:
		if delta.y > 0: _do_slide()
		else: _do_jump()


# ==============================================================================
#  UI
# ==============================================================================
func _make_label(size: int, col := Color.WHITE) -> Label:
	var l := Label.new()
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", col)
	l.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.85))
	l.add_theme_constant_override("outline_size", 6)
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	return l

func _build_ui() -> void:
	var layer := CanvasLayer.new()
	add_child(layer)
	# Full-screen white overlay for lightning flashes (behind the text).
	flash_rect = ColorRect.new()
	flash_rect.color = Color(1, 1, 1, 0)
	flash_rect.set_anchors_preset(Control.PRESET_FULL_RECT)
	flash_rect.mouse_filter = Control.MOUSE_FILTER_IGNORE
	layer.add_child(flash_rect)
	ui_score = _make_label(48); ui_score.horizontal_alignment = HORIZONTAL_ALIGNMENT_LEFT; layer.add_child(ui_score)
	ui_coins = _make_label(48, Color(1, 0.84, 0.3)); ui_coins.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT; layer.add_child(ui_coins)
	ui_info = _make_label(32, Color(0.9, 0.9, 0.9)); ui_info.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT; layer.add_child(ui_info)
	ui_caption = _make_label(40); layer.add_child(ui_caption)
	ui_tut = _make_label(34); layer.add_child(ui_tut)
	ui_center = _make_label(96, Color(1, 0.85, 0.3)); layer.add_child(ui_center)
	ui_sub = _make_label(44); layer.add_child(ui_sub)

func _vp() -> Vector2:
	return get_viewport().get_visible_rect().size

func _layout_ui() -> void:
	var s := _vp()
	ui_score.position = Vector2(s.x * 0.05, s.y * 0.04); ui_score.size = Vector2(s.x * 0.5, 60)
	ui_coins.position = Vector2(s.x * 0.45, s.y * 0.04); ui_coins.size = Vector2(s.x * 0.5, 60)
	ui_info.position = Vector2(s.x * 0.3, s.y * 0.04 + 56); ui_info.size = Vector2(s.x * 0.65, 40)
	ui_caption.position = Vector2(0, s.y * 0.16); ui_caption.size = Vector2(s.x, 60)
	ui_tut.position = Vector2(0, s.y * 0.24); ui_tut.size = Vector2(s.x, 60)
	ui_center.position = Vector2(0, s.y * 0.34); ui_center.size = Vector2(s.x, 120)
	ui_sub.position = Vector2(0, s.y * 0.52); ui_sub.size = Vector2(s.x, 200)

func _refresh_ui() -> void:
	match state:
		St.DEMO:
			ui_score.text = ""
			ui_coins.text = ""
			ui_info.text = "Best %d  •  %s  •  %s" % [high, _weather_name(), biomes[biome_idx]["name"]]
			ui_caption.text = "▶ DEMO   —   %s" % demo_caption
			ui_tut.text = ""
			ui_center.text = "RELIC RUSH"
			ui_sub.text = "Tap to play"
		St.PLAYING:
			ui_score.text = "Score  %d" % score
			ui_coins.text = "● %d" % coins
			ui_info.text = "Best %d  •  %s  •  %s" % [high, _weather_name(), biomes[biome_idx]["name"]]
			ui_center.text = ""
			ui_sub.text = ""
			ui_caption.text = _powerup_text()
			ui_tut.text = _tutorial_text()
		St.OVER:
			ui_caption.text = ""
			ui_tut.text = ""
			ui_center.text = "GAME OVER"
			ui_sub.text = "Score  %d\nCoins  %d\nBest  %d\n\nTap to try again" % [score, coins, high]

func _weather_name() -> String:
	return wB["name"] if (w_transition and w_blend > 0.5) else wA["name"]

func _tutorial_text() -> String:
	if game_time >= TUTORIAL_TIME:
		return ""
	if game_time < 3.0:
		return "◀ ▶  Swipe to switch lane"
	elif game_time < 6.0:
		return "▲  Swipe up to JUMP"
	elif game_time < 9.0:
		return "▼  Swipe down to SLIDE"
	return "Grab coins • dodge cars • survive!"

func _powerup_text() -> String:
	var t := ""
	if shield_t > 0.0: t += "[SHIELD %d]  " % int(ceil(shield_t))
	if magnet_t > 0.0: t += "[MAGNET %d]  " % int(ceil(magnet_t))
	if mult_t > 0.0: t += "[2X %d]  " % int(ceil(mult_t))
	if jet_t > 0.0: t += "[JETPACK %d]  " % int(ceil(jet_t))
	if tank_t > 0.0: t += "[TANK %d]  " % int(ceil(tank_t))
	if gun_t > 0.0: t += "[BAZOOKA %d]" % int(ceil(gun_t))
	return t


# ==============================================================================
#  Persistence
# ==============================================================================
func _load_high() -> void:
	var c := ConfigFile.new()
	if c.load(SAVE_PATH) == OK:
		high = int(c.get_value("score", "high", 0))

func _save_high() -> void:
	var c := ConfigFile.new()
	c.set_value("score", "high", high)
	c.save(SAVE_PATH)
