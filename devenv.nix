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
    run-mvn.exec = ''
      mvn javafx:run "$@"
    '';
    
    run-package.exec = ''
      echo "Building and running Paintera package..."
      nix run .#paintera -- "$@"
    '';
  };

  # https://devenv.sh/integrations/dotenv/
  dotenv.enable = true;

  # Enter shell message
  enterShell = ''
    echo "🎨 Paintera Development Environment"
    echo ""
    echo "Available commands:"
    echo "  run-mvn        - Run Paintera via Maven (mvn javafx:run)"
    echo "  run-package    - Build and run Paintera as a Nix package"
    echo ""
    echo "Java version: $(java -version 2>&1 | head -n 1)"
    echo "Maven version: $(mvn -version | head -n 1)"
    echo ""
  '';
}