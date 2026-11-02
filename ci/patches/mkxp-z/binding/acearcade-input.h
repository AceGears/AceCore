// AceArcade: live keyboard state for games that read the keyboard through the Windows API.
//
// Older Pokémon Essentials games (v16/v17: Pokémon Axon, Pokémon Ópalo, ...) replace the engine's Input module with
// their own, which reads each key with user32!GetAsyncKeyState and never calls the engine's Input.update. The
// engine's Input.raw_key_states is a snapshot taken by Input.update, so for those games it never changes and no key
// press reached them. GetAsyncKeyState on Windows reports the keyboard as it is at the moment of the call; this
// returns exactly that: the event thread's current key states (SDL scancode order), without touching the snapshot
// Input.update keeps, so trigger/repeat state of games that use the engine's Input is unchanged.
#pragma once

static VALUE aain_key_states(int argc, VALUE *argv, VALUE self) {
    (void)argc; (void)argv; (void)self;
    VALUE ret = rb_ary_new_capa(SDL_NUM_SCANCODES);
    for (int i = 0; i < SDL_NUM_SCANCODES; i++)
        rb_ary_push(ret, EventThread::keyStates[i] ? Qtrue : Qfalse);
    return ret;
}

static void aain_define() {
    VALUE input = rb_const_get(rb_cObject, rb_intern("Input"));
    rb_define_module_function(input, "acearcade_key_states", RUBY_METHOD_FUNC(aain_key_states), -1);
}
