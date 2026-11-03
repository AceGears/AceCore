.syntax unified
.arm
.org 0
b start
.org 0xC0
start:
 mov r0, #0x04000000
 mov r1, #0
 strh r1, [r0]
 ldr r4, =0x05000000
 ldr r5, =0x7C1F
 ldr r6, =0x03E0
 ldr r2, =0x02000000
 ldr r7, =0x02000100
 mov r1, #0
 strb r1, [r7]
 str r1, [r2]
 ldr r8, =0x0E000000
 mov r9, #0x41
 strb r9, [r8]
 mov r9, #0x43
 strb r9, [r8, #1]
 mov r9, #0x45
 strb r9, [r8, #2]
 mov r9, #0x21
 strb r9, [r8, #3]
loop:
 ldr r3, [r2]
 add r3, r3, #1
 str r3, [r2]
 ldrb r3, [r7]
 cmp r3, #0x42
 strhne r5, [r4]
 strheq r6, [r4]
 b loop
.ltorg
.balign 4
.asciz "SRAM_V113"
.balign 4
