package com.acecore.app.core.handheld.pokemon

/**
 * Addresses of the original Generation 3 Pokémon games, taken from the pret decompilation projects' symbol files
 * (github.com/pret/pokeemerald, pokefirered, pokeruby; "symbols" branch) for the exact dumps those projects build,
 * identified by SHA-1. RAM addresses are where the game keeps the party and bag; ROM addresses are the game's own data
 * tables (base stats, experience curves, move PP, item data). A ROM with any other SHA-1 (another language, a revision
 * not listed, a ROM hack) doesn't use these tools: hacks move and resize these structures.
 */
enum class Gen3Family(
    /** Pocket numbers in the item data (index into gBagPockets = pocket - 1). */
    val itemsPocket: Int, val keyPocket: Int, val ballsPocket: Int, val tmPocket: Int, val berriesPocket: Int,
    /** Most of one item a bag slot holds (the game's own limit). */
    val itemCap: Int, val berryCap: Int,
) {
    // pokeemerald include/constants/item.h; src/item.c (MAX_BAG_ITEM_CAPACITY 99, MAX_BERRY_CAPACITY 999)
    E(1, 5, 2, 3, 4, 99, 999),
    // pokefirered include/constants/global.h (POCKET_ITEMS 1, KEY_ITEMS 2, POKE_BALLS 3, TM_CASE 4, BERRY_POUCH 5); src/item.c (999)
    FRLG(1, 2, 3, 4, 5, 999, 999),
    // pokeruby include/item.h (same order as Emerald); src/item.c (99, berries 999)
    RS(1, 5, 2, 3, 4, 99, 999),
}

enum class Gen3Game(
    val label: String,
    val sha1: String,
    val family: Gen3Family,
    val party: Long,
    val partyCount: Long,
    /** gBagPockets: in RAM for Emerald / FireRed / LeafGreen, a ROM table for Ruby / Sapphire. */
    val bagPockets: Long,
    /** gSaveBlock2Ptr (Emerald / FRLG): the bag quantities are XORed with the save's encryption key at [keyOffset]. */
    val saveBlock2Ptr: Long?,
    val keyOffset: Int,
    val expTables: Long,
    val speciesInfo: Long,
    val speciesCount: Int,
    val speciesNames: Long,
    val battleMoves: Long,
    val moveCount: Int,
    val items: Long,
    val itemCount: Int,
) {
    EMERALD("Pokémon Emerald (USA)", "f3ae088181bf583e55daf962a92bb46f4f1d07b7", Gen3Family.E, party = 0x020244EC, partyCount = 0x020244E9, bagPockets = 0x02039DD8, saveBlock2Ptr = 0x03005D90, keyOffset = 0xAC, expTables = 0x0831F72C, speciesInfo = 0x083203CC, speciesCount = 412, speciesNames = 0x083185C8, battleMoves = 0x0831C898, moveCount = 355, items = 0x085839A0, itemCount = 377),
    FIRERED("Pokémon FireRed (USA)", "41cb23d8dccc8ebd7c649cd8fbb58eeace6e2fdc", Gen3Family.FRLG, party = 0x02024284, partyCount = 0x02024029, bagPockets = 0x0203988C, saveBlock2Ptr = 0x0300500C, keyOffset = 0xF20, expTables = 0x08253AE4, speciesInfo = 0x08254784, speciesCount = 412, speciesNames = 0x08245EE0, battleMoves = 0x08250C04, moveCount = 355, items = 0x083DB028, itemCount = 375),
    FIRERED_REV1("Pokémon FireRed (USA, Rev 1)", "dd5945db9b930750cb39d00c84da8571feebf417", Gen3Family.FRLG, party = 0x02024284, partyCount = 0x02024029, bagPockets = 0x0203988C, saveBlock2Ptr = 0x0300500C, keyOffset = 0xF20, expTables = 0x08253B54, speciesInfo = 0x082547F4, speciesCount = 412, speciesNames = 0x08245F50, battleMoves = 0x08250C74, moveCount = 355, items = 0x083DB098, itemCount = 375),
    LEAFGREEN("Pokémon LeafGreen (USA)", "574fa542ffebb14be69902d1d36f1ec0a4afd71e", Gen3Family.FRLG, party = 0x02024284, partyCount = 0x02024029, bagPockets = 0x0203988C, saveBlock2Ptr = 0x0300500C, keyOffset = 0xF20, expTables = 0x08253AC0, speciesInfo = 0x08254760, speciesCount = 412, speciesNames = 0x08245EBC, battleMoves = 0x08250BE0, moveCount = 355, items = 0x083DAE64, itemCount = 375),
    LEAFGREEN_REV1("Pokémon LeafGreen (USA, Rev 1)", "7862c67bdecbe21d1d69ce082ce34327e1c6ed5e", Gen3Family.FRLG, party = 0x02024284, partyCount = 0x02024029, bagPockets = 0x0203988C, saveBlock2Ptr = 0x0300500C, keyOffset = 0xF20, expTables = 0x08253B30, speciesInfo = 0x082547D0, speciesCount = 412, speciesNames = 0x08245F2C, battleMoves = 0x08250C50, moveCount = 355, items = 0x083DAED4, itemCount = 375),
    RUBY("Pokémon Ruby (USA)", "f28b6ffc97847e94a6c21a63cacf633ee5c8df1e", Gen3Family.RS, party = 0x03004360, partyCount = 0x03004350, bagPockets = 0x083C1618, saveBlock2Ptr = null, keyOffset = -1, expTables = 0x081FDF78, speciesInfo = 0x081FEC18, speciesCount = 412, speciesNames = 0x081F716C, battleMoves = 0x081FB12C, moveCount = 355, items = 0x083C5564, itemCount = 349),
    RUBY_REV1("Pokémon Ruby (USA, Rev 1)", "610b96a9c9a7d03d2bafb655e7560ccff1a6d894", Gen3Family.RS, party = 0x03004360, partyCount = 0x03004350, bagPockets = 0x083C1634, saveBlock2Ptr = null, keyOffset = -1, expTables = 0x081FDF90, speciesInfo = 0x081FEC30, speciesCount = 412, speciesNames = 0x081F7184, battleMoves = 0x081FB144, moveCount = 355, items = 0x083C5580, itemCount = 349),
    RUBY_REV2("Pokémon Ruby (USA, Rev 2)", "5b64eacf892920518db4ec664e62a086dd5f5bc8", Gen3Family.RS, party = 0x03004360, partyCount = 0x03004350, bagPockets = 0x083C1634, saveBlock2Ptr = null, keyOffset = -1, expTables = 0x081FDF90, speciesInfo = 0x081FEC30, speciesCount = 412, speciesNames = 0x081F7184, battleMoves = 0x081FB144, moveCount = 355, items = 0x083C5580, itemCount = 349),
    SAPPHIRE("Pokémon Sapphire (USA)", "3ccbbd45f8553c36463f13b938e833f652b793e4", Gen3Family.RS, party = 0x03004360, partyCount = 0x03004350, bagPockets = 0x083C1670, saveBlock2Ptr = null, keyOffset = -1, expTables = 0x081FDF08, speciesInfo = 0x081FEBA8, speciesCount = 412, speciesNames = 0x081F70FC, battleMoves = 0x081FB0BC, moveCount = 355, items = 0x083C55BC, itemCount = 349),
    SAPPHIRE_REV1("Pokémon Sapphire (USA, Rev 1)", "4722efb8cd45772ca32555b98fd3b9719f8e60a9", Gen3Family.RS, party = 0x03004360, partyCount = 0x03004350, bagPockets = 0x083C1690, saveBlock2Ptr = null, keyOffset = -1, expTables = 0x081FDF20, speciesInfo = 0x081FEBC0, speciesCount = 412, speciesNames = 0x081F7114, battleMoves = 0x081FB0D4, moveCount = 355, items = 0x083C55DC, itemCount = 349),
    SAPPHIRE_REV2("Pokémon Sapphire (USA, Rev 2)", "89b45fb172e6b55d51fc0e61989775187f6fe63c", Gen3Family.RS, party = 0x03004360, partyCount = 0x03004350, bagPockets = 0x083C1690, saveBlock2Ptr = null, keyOffset = -1, expTables = 0x081FDF20, speciesInfo = 0x081FEBC0, speciesCount = 412, speciesNames = 0x081F7114, battleMoves = 0x081FB0D4, moveCount = 355, items = 0x083C55DC, itemCount = 349)
    ;

    companion object {
        fun bySha1(sha1: String?): Gen3Game? = sha1?.lowercase()?.let { s -> entries.firstOrNull { it.sha1 == s } }
    }
}
