extends Node2D
# Walks a block down while "ui_down" is held (arrow Down, sent by AceArcade's on-screen D-pad) and reports
# "steps pressed frames" to <out>/godot_walk when started with "-- --out=<dir>".

var out := ""
var steps := 0
var held := 0.0
var frames := 0

func _ready() -> void:
	for a in OS.get_cmdline_user_args():
		if a.begins_with("--out="):
			out = a.substr(6)
	_write("godot_started", Engine.get_version_info().string)

func _process(delta: float) -> void:
	frames += 1
	var down := Input.is_action_pressed("ui_down")
	if down:
		held += delta
		while held >= 0.25:
			held -= 0.25
			steps += 1
			$Hero.position.y += 8
	else:
		held = 0.0
	if frames % 5 == 0:
		_write("godot_walk", "%d %d %d" % [steps, 1 if down else 0, frames])

func _write(file_name: String, text: String) -> void:
	if out == "":
		return
	var f := FileAccess.open(out.path_join(file_name), FileAccess.WRITE)
	if f:
		f.store_string(text)
