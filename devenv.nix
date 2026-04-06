{ pkgs, ... }:

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


  enterShell = ''
    echo "Paintera dev shell — JDK $(java -version 2>&1 | head -1)"
  '';
}
