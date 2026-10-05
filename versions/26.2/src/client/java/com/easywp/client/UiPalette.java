package com.easywp.client;

/** Paleta central de las pantallas propias del mod (no aplica a ModConfigScreen, que es vanilla). */
public final class UiPalette {
    private UiPalette() {}

    // Chrome del modal: paleta azul semitransparente original (revertida tras probar el negro casi puro)
    public static final int MODAL_BG     = 0xD010141E;
    public static final int MODAL_BORDER = 0xFF3D4966;
    // Fondo del menú "Más opciones" de la lista: casi opaco, porque flota sobre las filas y sus botones.
    public static final int MENU_BG      = 0xF210141E;

    // Estados de ModernButton: mismos valores azules originales
    public static final int BTN_BG           = 0xB0141A26;
    public static final int BTN_BORDER       = 0xFF3B4866;
    public static final int BTN_TEXT         = 0xFFD0D0D0;
    public static final int BTN_BG_HOVER     = 0xE025324A;
    public static final int BTN_BORDER_HOVER = 0xFF5E75A8;
    public static final int BTN_TEXT_HOVER   = 0xFFFFFFFF;
    public static final int BTN_BG_OFF       = 0x3010141E;
    public static final int BTN_BORDER_OFF   = 0x50252D40;
    public static final int BTN_TEXT_OFF     = 0xFF666666;

    // Tintes de icono: retocados con un matiz azulado para acompañar la paleta de arriba.
    // ICON_DESTRUCTIVE y ICON_ACCENT se dejan igual: son colores semánticos (rojo = borrar,
    // morado = teletransporte) que no dependen del tema.
    public static final int ICON_IDLE        = 0xFFBCC8DE;
    public static final int ICON_DISABLED    = 0xFF4A5570;
    public static final int ICON_DESTRUCTIVE = 0xFFD45C5C;
    public static final int ICON_ACCENT      = 0xFFC88CFF;

    // Marcador de la lista: colores fijos que replican el marcador real del mundo 3D, no cambian con el tema.
    public static final int MARKER_OUTLINE      = 0xFF000000;
    public static final int MARKER_SKULL        = 0xFFF0EEE6;
    public static final int FOCUS_OUTLINE_ON    = 0xFFD4AF37;

    // Estrella de favorito: ámbar, el color universal de "favorito". Distinto del dorado del foco
    // (FOCUS_OUTLINE_ON), que significa otra cosa; la forma tampoco se parece al borde del foco.
    public static final int FAVORITE_STAR = 0xFFFFC83D;

    // Fondo de la etiqueta del waypoint: el HUD (WaypointRenderer) y la vista previa (no cambia con el tema)
    public static final int LABEL_BACKDROP = 0x40000000;

    // Texto
    public static final int TEXT_PRIMARY   = 0xFFFFFFFF;
    public static final int TEXT_SECONDARY = 0xFF7C88A6;
    public static final int TEXT_DIM       = 0xFF4A5570;
    public static final int TEXT_DIM_SOFT  = 0xFF2E3446;

    // Aviso transitorio de "Copiado" junto a las coordenadas tras un doble clic - mismo tono que
    // DIM_OVERWORLD pero con su propio nombre, para que un futuro cambio de los colores de
    // dimensión no re-tiña este texto sin relación con ellos.
    public static final int TEXT_SUCCESS = 0xFF5BC93B;

    // Acentos
    public static final int DIM_OVERWORLD = 0xFF5BC93B;
    public static final int DIM_NETHER    = 0xFFE85F5F;
    public static final int DIM_END       = 0xFFC06EFF;
    public static final int SCROLL_ARROW  = 0xFF3D4966;
    public static final int SCROLL_TRACK  = 0xFF1C2233;
    public static final int SCROLL_THUMB  = 0xFF7C88A6;
    public static final int SWATCH_HOVER  = 0x60546EA0;
}
