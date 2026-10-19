package io.auralis.desktop.ui;

import javafx.scene.Parent;
import javafx.scene.paint.Color;

/** Semantic palette shared by controls, canvas, and GPU scenes. Existing keys remain stable. */
public enum Theme {
    OBSIDIAN_YELLOW("Obsidian Yellow", "#08090b", "#111216", "#1b1c21", "#f8d34a", "#ff9152", "#f5f5f2", "#92949b", "#14110a"),
    MONOCHROME("Monochrome", "#080808", "#141414", "#202020", "#f2f2f2", "#85858a", "#fafafa", "#a5a5aa", "#080808"),
    CRIMSON_NOIR("Crimson Noir", "#0d080b", "#1a1015", "#29171e", "#ed637b", "#941b3d", "#fff0f3", "#ad929b", "#21090f"),
    DEEP_VIOLET("Deep Violet", "#0b0911", "#15111e", "#241a33", "#b197fc", "#7253d9", "#f1edf8", "#a29aaa", "#140c24"),
    ARCTIC_SILVER("Arctic Silver", "#0c1014", "#151b22", "#242d36", "#c6d6e4", "#7d9aae", "#f0f4f7", "#96a6b5", "#0f1922"),
    JADE("Jade · дополнительная", "#101512", "#171f1b", "#1b2420", "#8cbf9f", "#568f77", "#f0f4f1", "#91a198", "#101a14"),
    AMBER("Amber · дополнительная", "#171513", "#1e1b17", "#25211c", "#d6aa6c", "#a66d47", "#f4f0e9", "#a59c8f", "#20180d"),
    AZURE("Azure · дополнительная", "#101319", "#171c25", "#1b2330", "#8ea9e8", "#586fc2", "#f0f3fa", "#969fb0", "#10182a");
    private final String label,background,panel,surface,accent,secondary,text,muted,ink;
    Theme(String label,String background,String panel,String surface,String accent,String secondary,String text,String muted,String ink) {
        this.label=label;this.background=background;this.panel=panel;this.surface=surface;this.accent=accent;this.secondary=secondary;this.text=text;this.muted=muted;this.ink=ink;
    }
    public String accent(){return accent;} public String background(){return background;} public String panel(){return panel;}
    public String surface(){return surface;} public String secondary(){return secondary;}
    public Color accentColor(){return Color.web(accent);} public Color secondaryColor(){return Color.web(secondary);} public Color backgroundColor(){return Color.web(background);}
    public void apply(Parent root) {root.setStyle("-bg:"+background+";-panel:"+panel+";-surface:"+surface+";-accent:"+accent+";-secondary:"+secondary+";-text:"+text+";-muted:"+muted+";-ink:"+ink+";");}
    @Override public String toString(){return label;}
}
