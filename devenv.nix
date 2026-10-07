{ pkgs, lib, ... }:

{
  cachix.push = "paintera";

  languages.java = {
    enable = true;
    jdk.package = pkgs.zulu25.override {
      enableJavaFX = true;
    };
    maven.enable = true;
    lsp.enable = false;
  };

  env.LD_LIBRARY_PATH = lib.makeLibraryPath (with pkgs; [
    gtk3
    glib
    pango
    cairo
    at-spi2-core
    gdk-pixbuf
    libglvnd
    mesa
    xorg.libX11
    xorg.libXtst
    xorg.libXxf86vm
    c-blosc
    gst_all_1.gstreamer
    gst_all_1.gst-plugins-base
  ]);

  enterShell = ''
    echo "Paintera dev shell — JDK $(java -version 2>&1 | head -1)"
  '';
}
