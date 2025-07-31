{ pkgs, lib, config, inputs, ... }:

{
  # https://devenv.sh/basics/
  env.GREET = "devenv";

  # https://devenv.sh/packages/
  packages = [ 
    pkgs.git 
    pkgs.maven
    pkgs.c-blosc
    (pkgs.openjdk21.override { enableJavaFX = true; })
  ];

  # Environment variables
  env = {
    JAVA_HOME = "${pkgs.openjdk21.override { enableJavaFX = true; }}/lib/openjdk";
    LD_LIBRARY_PATH = "${pkgs.c-blosc}/lib";
    DYLD_LIBRARY_PATH = "${pkgs.c-blosc}/lib";
  };

  # https://devenv.sh/scripts/
  scripts = {
    paintera.exec = ''
      mvn javafx:run
    '';
  };

  # https://devenv.sh/integrations/dotenv/
  dotenv.enable = true;

  # Enter shell message
  enterShell = ''
    echo "🎨 Paintera Development Environment"
    echo ""
    echo "Available commands:"
    echo "  paintera       - Run Paintera via Maven (mvn javafx:run)"
    echo ""
    echo "Java version: $(java -version 2>&1 | head -n 1)"
    echo "Maven version: $(mvn -version | head -n 1)"
    echo ""
  '';
}