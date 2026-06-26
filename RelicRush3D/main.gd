extends Node3D
## Relic Rush — a 3D two-lane city endless runner.
##
## The entire world (camera, lights, player, obstacles, scenery, weather and UI)
## is built procedurally from Godot primitive meshes, so the project has no
## imported art assets and is fully original. Drop in real 3D models later and
## swap them for the blockout meshes built here.

# ---- Tuning -------------------------------------------------------------------
const LANE_X := 1.7           # half-distance between the two lanes
const SPAWN_Z := -85.0        # how far ahead things appear (longer sightline)
const CULL_Z := 12.0          # behind the camera -> recycle / remove
const START_SPEED := 9.0      # gentler start
const MAX_SPEED := 20.0       # lower top speed -> more reaction time
const ACCEL := 0.30           # slower ramp
const GRACE := 3.0
const TUTORIAL_TIME := 10.0
const GAP_Z := 16.0           # more spacing between obstacle rows
const JUMP_VELOCITY := 7.8
const GRAVITY := -16.0        # a touch more air time
const JUMP_CLEAR := 0.7
const SLIDE_TIME := 0.75
const WEATHER_FADE := 5.0
const COIN_VALUE := 10
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
var p_y := 0.0
var p_vy := 0.0
var jumping := false
var sliding := false
var slide_t := 0.0

# nodes
var cam: Camera3D
var sun: DirectionalLight3D
var env: Environment
var sky_mat: ProceduralSkyMaterial
var player: Node3D
var upper: Node3D          # upper-body group (leans forward when sliding)
var arm_l_piv: Node3D
var arm_r_piv: Node3D
var leg_l_piv: Node3D
var leg_r_piv: Node3D
var scarf: Node3D
var board: Node3D

var obstacles: Array = []      # [{node, type, lane, resolved}]
var coin_nodes: Array = []     # [{node, lane, taken}]
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
	_build_ground()
	_build_player()
	_build_weather_particles()
	_build_ui()
	_randomize_weather()
	demo_caption = "Watch the demo…"


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

func _build_environment() -> void:
	var we := WorldEnvironment.new()
	env = Environment.new()
	env.background_mode = Environment.BG_SKY
	var sky := Sky.new()
	sky_mat = ProceduralSkyMaterial.new()
	sky_mat.sky_top_color = Color(0.29, 0.52, 0.81)
	sky_mat.sky_horizon_color = Color(0.8, 0.86, 0.88)
	sky_mat.ground_horizon_color = Color(0.8, 0.86, 0.88)
	sky_mat.ground_bottom_color = Color(0.4, 0.45, 0.4)
	sky.sky_material = sky_mat
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 1.0
	env.tonemap_mode = Environment.TONE_MAPPER_ACES
	# Bloom makes coins, headlights and lit windows glow for a richer look.
	env.glow_enabled = true
	env.glow_intensity = 0.7
	env.glow_bloom = 0.15
	env.glow_hdr_threshold = 1.0
	we.environment = env
	add_child(we)

	sun = DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-52, -38, 0)
	sun.light_energy = 1.15
	sun.shadow_enabled = true
	sun.shadow_blur = 1.5
	sun.directional_shadow_max_distance = 100.0
	add_child(sun)

func _build_camera() -> void:
	cam = Camera3D.new()
	cam.fov = 68.0
	cam.position = Vector3(0, 4.3, 8.0)
	cam.look_at_from_position(cam.position, Vector3(0, 0.9, -14), Vector3.UP)
	add_child(cam)

func _build_ground() -> void:
	# Wide surrounding ground (sidewalks / lots).
	var surround := MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = Vector2(80, 260)
	surround.mesh = pm
	surround.material_override = _mat(Color(0.34, 0.36, 0.34))
	surround.position = Vector3(0, -0.02, -90)
	add_child(surround)

	# Asphalt road.
	var road := MeshInstance3D.new()
	var rpm := PlaneMesh.new()
	rpm.size = Vector2(LANE_X * 2.0 + 2.2, 260)
	road.mesh = rpm
	road.material_override = _mat(Color(0.13, 0.13, 0.15))
	road.position = Vector3(0, 0.0, -90)
	add_child(road)

	# Centre dashed line (pool, recycled forward).
	var n := 26
	for i in n:
		var d := _box(Vector3(0.18, 0.02, 2.2), Color(0.92, 0.78, 0.25, 1), self)
		d.position = Vector3(0, 0.02, SPAWN_Z + i * (GAP_Z * 0.0 + 4.0))
		dashes.append(d)

	# Pre-create a pool of side buildings (recycled).
	for i in 16:
		var node := _make_building()
		node.position = Vector3(0, 0, 200)  # parked off-screen until spawned
		buildings.append({"node": node, "active": false})

func _make_building() -> Node3D:
	var b := Node3D.new()
	add_child(b)
	var w := randf_range(3.0, 5.0)
	var h := randf_range(8.0, 26.0)
	var depth := randf_range(3.0, 6.0)
	var base := _box(Vector3(w, h, depth), Color(0.42, 0.45, 0.5), b, Vector3(0, h * 0.5, 0))
	base.set_meta("h", h)
	# Window grid as a single emissive front panel that lights up at night.
	var win := _box(Vector3(w * 0.92, h * 0.92, 0.1), Color(0.16, 0.18, 0.24), b, Vector3(0, h * 0.5, depth * 0.5 + 0.06))
	win.set_meta("win", true)
	b.set_meta("h", h)
	b.set_meta("win", win)
	return b

func _build_player() -> void:
	# An ORIGINAL anime-style ninja runner (not based on any existing character):
	# spiky hair, a blank steel headband and a trailing scarf. Built smaller and
	# more defined than the first blockout, with rounded capsule/sphere limbs and
	# pivots so the limbs swing naturally.
	player = Node3D.new()
	add_child(player)
	var skin := Color(0.96, 0.82, 0.66)
	var outfit := Color(0.16, 0.18, 0.24)     # charcoal-navy gi
	var sleeve := Color(0.24, 0.26, 0.32)
	var pants := Color(0.13, 0.14, 0.17)
	var crimson := Color(0.78, 0.16, 0.2)     # scarf / accent
	var hair_c := Color(0.09, 0.09, 0.15)
	var steel := Color(0.62, 0.64, 0.7)

	# Skateboard.
	board = Node3D.new(); player.add_child(board); board.position = Vector3(0, 0.1, 0)
	_box(Vector3(0.8, 0.08, 0.3), Color(0.22, 0.76, 0.84), board, Vector3(0, 0, 0))
	_box(Vector3(0.8, 0.04, 0.3), Color(0.12, 0.5, 0.6), board, Vector3(0, -0.04, 0))
	for zx in [-0.28, 0.28]:
		var w := _cyl(0.1, 0.08, Color(0.95, 0.92, 0.85), board, Vector3(zx, -0.07, 0.0))
		w.rotation_degrees = Vector3(0, 0, 90)

	# Legs (pivot at the hips so they swing).
	leg_l_piv = Node3D.new(); player.add_child(leg_l_piv); leg_l_piv.position = Vector3(-0.13, 0.6, 0)
	_cap(0.1, 0.46, pants, leg_l_piv, Vector3(0, -0.26, 0))
	_box(Vector3(0.16, 0.08, 0.3), Color(0.1, 0.1, 0.12), leg_l_piv, Vector3(0, -0.5, 0.06))
	leg_r_piv = Node3D.new(); player.add_child(leg_r_piv); leg_r_piv.position = Vector3(0.13, 0.6, 0)
	_cap(0.1, 0.46, pants, leg_r_piv, Vector3(0, -0.26, 0))
	_box(Vector3(0.16, 0.08, 0.3), Color(0.1, 0.1, 0.12), leg_r_piv, Vector3(0, -0.5, 0.06))

	# Upper body group — pivots at the hip so it can lean for sliding.
	upper = Node3D.new(); player.add_child(upper); upper.position = Vector3(0, 0.6, 0)
	_cap(0.24, 0.5, outfit, upper, Vector3(0, 0.34, 0))                       # torso
	_box(Vector3(0.5, 0.1, 0.36), crimson, upper, Vector3(0, 0.18, 0))        # belt/sash

	# Arms (pivots at shoulders).
	arm_l_piv = Node3D.new(); upper.add_child(arm_l_piv); arm_l_piv.position = Vector3(-0.28, 0.52, 0)
	_cap(0.08, 0.42, sleeve, arm_l_piv, Vector3(0, -0.2, 0))
	_sph(0.09, skin, arm_l_piv, Vector3(0, -0.42, 0))
	arm_r_piv = Node3D.new(); upper.add_child(arm_r_piv); arm_r_piv.position = Vector3(0.28, 0.52, 0)
	_cap(0.08, 0.42, sleeve, arm_r_piv, Vector3(0, -0.2, 0))
	_sph(0.09, skin, arm_r_piv, Vector3(0, -0.42, 0))

	# Head + face.
	_sph(0.23, skin, upper, Vector3(0, 0.86, 0))
	_box(Vector3(0.06, 0.06, 0.02), Color(0.1, 0.1, 0.12), upper, Vector3(-0.09, 0.88, 0.21))  # eyes
	_box(Vector3(0.06, 0.06, 0.02), Color(0.1, 0.1, 0.12), upper, Vector3(0.09, 0.88, 0.21))

	# Spiky hair (several prisms fanning up and back).
	for a in [-0.28, -0.1, 0.1, 0.28]:
		var spike := _prism(Vector3(0.16, 0.3, 0.14), hair_c, upper, Vector3(a, 1.04, -0.04))
		spike.rotation_degrees = Vector3(-18, 0, a * 30.0)
	_prism(Vector3(0.18, 0.26, 0.16), hair_c, upper, Vector3(0, 1.0, -0.12)).rotation_degrees = Vector3(-40, 0, 0)

	# Steel headband with a blank plate (no symbol — original).
	_box(Vector3(0.5, 0.1, 0.5), steel, upper, Vector3(0, 0.96, 0)).material_override = _mat(steel, 0.0, 0.6, 0.35)
	_box(Vector3(0.2, 0.12, 0.03), Color(0.8, 0.82, 0.86), upper, Vector3(0, 0.96, 0.23)).material_override = _mat(Color(0.8, 0.82, 0.86), 0.0, 0.8, 0.25)

	# Trailing scarf.
	scarf = Node3D.new(); upper.add_child(scarf); scarf.position = Vector3(0, 0.62, -0.05)
	_box(Vector3(0.42, 0.16, 0.34), crimson, scarf, Vector3(0, 0, 0))
	_prism(Vector3(0.2, 0.7, 0.12), crimson, scarf, Vector3(-0.1, -0.1, -0.3)).rotation_degrees = Vector3(110, 0, 0)

	player.position = Vector3(p_x, 0, 0)

func _build_weather_particles() -> void:
	rain_root = Node3D.new(); cam.add_child(rain_root)
	snow_root = Node3D.new(); cam.add_child(snow_root)
	for i in 90:
		var d := _box(Vector3(0.02, 0.5, 0.02), Color(0.8, 0.86, 0.95, 1), rain_root)
		d.position = Vector3(randf_range(-6, 6), randf_range(-5, 6), -randf_range(3, 12))
		rain_drops.append(d)
	for i in 70:
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
		 "fog": 0.004, "fog_c": Color(0.9,0.6,0.45), "precip": 0},
		{"name": "Night", "top": Color(0.03,0.05,0.13), "hor": Color(0.12,0.16,0.3),
		 "sun": Color(0.5,0.6,0.85), "sun_e": 0.28, "amb": 0.28, "night": 1.0,
		 "fog": 0.0, "fog_c": Color(0.1,0.12,0.2), "precip": 0},
		{"name": "Dawn", "top": Color(0.34,0.42,0.66), "hor": Color(1,0.82,0.7),
		 "sun": Color(1,0.86,0.74), "sun_e": 0.9, "amb": 0.7, "night": 0.15,
		 "fog": 0.006, "fog_c": Color(0.85,0.78,0.8), "precip": 0},
		{"name": "Rain", "top": Color(0.22,0.25,0.3), "hor": Color(0.45,0.5,0.55),
		 "sun": Color(0.7,0.74,0.8), "sun_e": 0.5, "amb": 0.5, "night": 0.4,
		 "fog": 0.012, "fog_c": Color(0.45,0.5,0.55), "precip": 1},
		{"name": "Snow", "top": Color(0.58,0.66,0.77), "hor": Color(0.88,0.91,0.95),
		 "sun": Color(0.9,0.93,0.98), "sun_e": 0.8, "amb": 0.85, "night": 0.1,
		 "fog": 0.01, "fog_c": Color(0.85,0.9,0.95), "precip": 2},
		{"name": "Fog", "top": Color(0.59,0.6,0.62), "hor": Color(0.8,0.81,0.82),
		 "sun": Color(0.85,0.85,0.85), "sun_e": 0.6, "amb": 0.7, "night": 0.15,
		 "fog": 0.03, "fog_c": Color(0.8,0.81,0.82), "precip": 0},
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
	sky_mat.sky_top_color = wA["top"].lerp(wB["top"], t)
	sky_mat.sky_horizon_color = wA["hor"].lerp(wB["hor"], t)
	sky_mat.ground_horizon_color = sky_mat.sky_horizon_color
	sun.light_color = wA["sun"].lerp(wB["sun"], t)
	sun.light_energy = _lf(wA["sun_e"], wB["sun_e"])
	env.ambient_light_energy = _lf(wA["amb"], wB["amb"])
	var fog: float = _lf(wA["fog"], wB["fog"])
	env.fog_enabled = fog > 0.0005
	env.fog_density = fog
	env.fog_light_color = wA["fog_c"].lerp(wB["fog_c"], t)

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
	_step_player(dt)
	dist_to_spawn -= speed * dt
	if dist_to_spawn <= 0.0:
		if game_time > GRACE:
			_spawn_row()
		dist_to_spawn += GAP_Z * randf_range(0.85, 1.45)
	_advance(dt)
	score += int(speed * dt * 6.0)

func _update_demo(dt: float) -> void:
	speed = 13.0
	_step_player(dt)
	demo_timer -= speed * dt
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
			# Construction barrier on A-frame legs with a glowing top bar — JUMP it.
			_box(Vector3(0.14, 1.0, 0.5), Color(0.82, 0.82, 0.86), n, Vector3(-0.74, 0.5, 0))
			_box(Vector3(0.14, 1.0, 0.5), Color(0.82, 0.82, 0.86), n, Vector3(0.74, 0.5, 0))
			_box(Vector3(1.74, 0.42, 0.16), Color(0.95, 0.45, 0.1), n, Vector3(0, 0.8, 0.16))
			for sx in [-0.6, -0.2, 0.2, 0.6]:
				_box(Vector3(0.2, 0.44, 0.17), Color(0.95, 0.95, 0.96), n, Vector3(sx, 0.8, 0.17))
			_box(Vector3(1.78, 0.1, 0.18), Color(1.0, 0.85, 0.2), n, Vector3(0, 1.03, 0.16)).material_override = _mat(Color(1.0, 0.85, 0.2), 1.4)
		"OVERHANG":
			# Sign gantry with a low caution bar to duck under — SLIDE under it.
			_box(Vector3(0.2, 2.8, 0.2), Color(0.5, 0.55, 0.6), n, Vector3(-0.96, 1.4, 0))
			_box(Vector3(0.2, 2.8, 0.2), Color(0.5, 0.55, 0.6), n, Vector3(0.96, 1.4, 0))
			_box(Vector3(2.3, 0.3, 0.3), Color(0.45, 0.5, 0.55), n, Vector3(0, 2.65, 0))
			_box(Vector3(1.9, 0.7, 0.1), Color(0.2, 0.5, 0.85), n, Vector3(0, 2.2, 0.16)).material_override = _mat(Color(0.2, 0.5, 0.85), 0.6)
			_box(Vector3(1.95, 0.45, 0.2), Color(0.9, 0.2, 0.2), n, Vector3(0, 1.35, 0))
			for sx in [-0.6, -0.2, 0.2, 0.6]:
				_box(Vector3(0.18, 0.47, 0.21), Color(0.96, 0.96, 0.96), n, Vector3(sx, 1.35, 0.01))
		"CAR":
			_make_car(n)
	n.position = Vector3(lx, 0, SPAWN_Z)
	obstacles.append({"node": n, "type": type, "lane": lane, "resolved": false})

func _make_car(parent: Node3D) -> void:
	var col: Color = [Color(0.85,0.18,0.18), Color(0.16,0.38,0.85), Color(0.95,0.78,0.18), Color(0.16,0.7,0.42)][randi() % 4]
	# Lower body + skirt.
	_box(Vector3(1.8, 0.6, 3.7), col, parent, Vector3(0, 0.62, 0))
	_box(Vector3(1.8, 0.32, 3.7), col.darkened(0.35), parent, Vector3(0, 0.34, 0))
	# Cabin + glass wrap.
	_box(Vector3(1.55, 0.55, 1.9), col, parent, Vector3(0, 1.1, -0.1))
	var glass := Color(0.55, 0.75, 0.92)
	_box(Vector3(1.4, 0.42, 0.08), glass, parent, Vector3(0, 1.12, 0.83))     # rear window
	_box(Vector3(0.08, 0.42, 1.7), glass, parent, Vector3(-0.76, 1.12, -0.1)) # left
	_box(Vector3(0.08, 0.42, 1.7), glass, parent, Vector3(0.76, 1.12, -0.1))  # right
	_box(Vector3(1.7, 0.06, 1.9), col.lightened(0.15), parent, Vector3(0, 1.4, -0.1)) # roof
	# Glowing tail lights + bumper.
	for tx in [-0.6, 0.6]:
		_box(Vector3(0.36, 0.16, 0.08), Color(1, 0.22, 0.18), parent, Vector3(tx, 0.66, 1.86)).material_override = _mat(Color(1, 0.25, 0.2), 1.6)
	_box(Vector3(1.82, 0.18, 0.12), Color(0.12, 0.12, 0.14), parent, Vector3(0, 0.42, 1.86))
	# Wheels with hubcaps.
	for zx in [-0.88, 0.88]:
		for zz in [-1.2, 1.2]:
			var w := _cyl(0.36, 0.26, Color(0.06,0.06,0.07), parent, Vector3(zx, 0.36, zz))
			w.rotation_degrees = Vector3(0, 0, 90)
			_cyl(0.16, 0.28, Color(0.7,0.72,0.76), parent, Vector3(zx, 0.36, zz)).rotation_degrees = Vector3(0, 0, 90)

func _add_coin(lane: int) -> void:
	var n := Node3D.new()
	add_child(n)
	var c := _cyl(0.45, 0.12, Color(0.98, 0.78, 0.2), n, Vector3.ZERO)
	c.rotation_degrees = Vector3(90, 0, 0)
	c.material_override = _mat(Color(0.98, 0.78, 0.2), 0.6)
	n.position = Vector3(_lane_x(lane), 1.1, SPAWN_Z)
	coin_nodes.append({"node": n, "lane": lane, "taken": false})

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
	# coin in a lane with no car
	var blocked := {}
	for o in obstacles:
		if o["node"].position.z == SPAWN_Z and o["type"] == "CAR":
			blocked[o["lane"]] = true
	var free := []
	for l in [0, 1]:
		if not blocked.has(l):
			free.append(l)
	if free.size() > 0 and randf() < 0.85:
		_add_coin(free[randi() % free.size()])

func _advance(dt: float) -> void:
	var move := speed * dt
	for o in obstacles:
		o["node"].position.z += move
		if not o["resolved"] and o["node"].position.z >= 0.0:
			o["resolved"] = true
			if state == St.PLAYING and int(o["lane"]) == _round_lane() and not _survives(o):
				_game_over()
	for c in coin_nodes:
		c["node"].position.z += move
		c["node"].rotate_y(dt * 4.0)
		if not c["taken"] and c["node"].position.z >= 0.0 and int(c["lane"]) == _round_lane():
			c["taken"] = true
			c["node"].visible = false
			if state == St.PLAYING:
				coins += 1
				score += COIN_VALUE
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
	p_x = lerp(p_x, target_x, clamp(dt * 12.0, 0.0, 1.0))
	if jumping:
		p_y += p_vy * dt
		p_vy += GRAVITY * dt
		if p_y <= 0.0:
			p_y = 0.0; p_vy = 0.0; jumping = false
	if sliding:
		slide_t -= dt
		if slide_t <= 0.0:
			sliding = false
	player.position.x = p_x
	player.position.y = p_y

func _animate_player(dt: float) -> void:
	# Lean into turns; swing limbs; crouch when sliding; flow the scarf.
	var lean: float = clamp((_lane_x(p_lane) - p_x) * 0.6, -0.5, 0.5)
	player.rotation.z = -lean
	var swing: float = sin(run_cycle) * 0.7
	scarf.rotation.x = -0.4 - 0.2 * sin(run_cycle * 0.7) - clamp(speed * 0.02, 0.0, 0.7)
	if sliding:
		upper.rotation.x = -1.1
		upper.position.y = 0.42
		leg_l_piv.rotation.x = 1.3
		leg_r_piv.rotation.x = 0.9
		arm_l_piv.rotation.x = 1.3
		arm_r_piv.rotation.x = 1.3
	else:
		upper.rotation.x = lerp(upper.rotation.x, -0.06, clamp(dt * 12.0, 0.0, 1.0))
		upper.position.y = 0.6
		if not jumping:
			leg_l_piv.rotation.x = swing
			leg_r_piv.rotation.x = -swing
			arm_l_piv.rotation.x = -swing * 0.8
			arm_r_piv.rotation.x = swing * 0.8
		else:
			leg_l_piv.rotation.x = 0.6
			leg_r_piv.rotation.x = 0.7
			arm_l_piv.rotation.x = -1.2
			arm_r_piv.rotation.x = -1.2


# ==============================================================================
#  Scenery
# ==============================================================================
func _scroll_dashes(dt: float) -> void:
	var move := speed * dt
	for d in dashes:
		d.position.z += move
		if d.position.z > CULL_Z:
			d.position.z -= dashes.size() * 4.0

func _update_buildings(dt: float) -> void:
	var move := speed * dt
	for b in buildings:
		if b["active"]:
			b["node"].position.z += move
			if b["node"].position.z > CULL_Z + 8.0:
				b["active"] = false
				b["node"].position = Vector3(0, 0, 200)
	build_timer -= speed * dt
	if build_timer <= 0.0:
		build_timer = randf_range(6.0, 11.0)
		for side in [-1.0, 1.0]:
			var slot = _free_building()
			if slot != null:
				slot["active"] = true
				var off := randf_range(5.5, 9.0)
				slot["node"].position = Vector3(side * (LANE_X + off), 0, SPAWN_Z - randf_range(0, 6))

func _free_building():
	for b in buildings:
		if not b["active"]:
			return b
	return null

func _light_windows() -> void:
	var nf := _night_factor()
	for b in buildings:
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
	obstacles.clear()
	coin_nodes.clear()
	p_lane = 0; p_x = -LANE_X; p_y = 0.0; p_vy = 0.0
	jumping = false; sliding = false
	speed = START_SPEED
	dist_to_spawn = GAP_Z
	game_time = 0.0; score = 0; coins = 0
	state = St.PLAYING

func _game_over() -> void:
	state = St.OVER
	crash_flash = 1.0
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

func _do_slide() -> void:
	if not jumping and not sliding:
		sliding = true
		slide_t = SLIDE_TIME

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
			ui_info.text = "Best %d  •  %s" % [high, _weather_name()]
			ui_caption.text = "▶ DEMO   —   %s" % demo_caption
			ui_tut.text = ""
			ui_center.text = "RELIC RUSH"
			ui_sub.text = "Tap to play"
		St.PLAYING:
			ui_score.text = "Score  %d" % score
			ui_coins.text = "● %d" % coins
			ui_info.text = "Best %d  •  %s" % [high, _weather_name()]
			ui_center.text = ""
			ui_sub.text = ""
			ui_caption.text = ""
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
