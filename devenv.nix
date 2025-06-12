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
    build.exec = ''
      mvn clean package -DskipTests
    '';
    
    test.exec = ''
      mvn test
    '';
    
    run-mvn.exec = ''
      mvn javafx:run
    '';
    
    run-java.exec = ''
      java -XX:MaxRAMPercentage=80 \
        --add-modules ALL-SYSTEM \
        --add-opens javafx.base/javafx.util=ALL-UNNAMED \
        --add-opens javafx.base/javafx.event=ALL-UNNAMED \
        --add-opens javafx.base/javafx.beans.property=ALL-UNNAMED \
        --add-opens javafx.base/com.sun.javafx.binding=ALL-UNNAMED \
        --add-opens javafx.base/com.sun.javafx.event=ALL-UNNAMED \
        --add-opens javafx.graphics/javafx.scene=ALL-UNNAMED \
        --add-opens javafx.graphics/javafx.stage=ALL-UNNAMED \
        --add-opens javafx.graphics/javafx.geometry=ALL-UNNAMED \
        --add-opens javafx.graphics/javafx.animation=ALL-UNNAMED \
        --add-opens javafx.graphics/javafx.scene.input=ALL-UNNAMED \
        --add-opens javafx.graphics/javafx.scene.image=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.prism=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.javafx.application=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.javafx.geom=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.javafx.image=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.javafx.stage=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.javafx.perf=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.javafx.cursor=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.javafx.scene.traversal=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.javafx.geom.transform=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.scenario.animation=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.scenario.animation.shared=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.scenario.effect=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.javafx.sg.prism=ALL-UNNAMED \
        --add-opens javafx.graphics/com.sun.prism.paint=ALL-UNNAMED \
        --add-opens javafx.controls/javafx.scene.control=ALL-UNNAMED \
        --add-exports javafx.controls/com.sun.javafx.scene.control=ALL-UNNAMED \
        --add-exports javafx.graphics/com.sun.javafx.application=ALL-UNNAMED \
        --add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED \
        --add-exports javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED \
        --add-exports javafx.graphics/com.sun.javafx.stage=ALL-UNNAMED \
        --add-exports javafx.graphics/com.sun.javafx.image=ALL-UNNAMED \
        --add-exports javafx.base/com.sun.javafx=ALL-UNNAMED \
        -cp "target/paintera-*.jar:target/dependency/*" \
        org.janelia.saalfeldlab.paintera.Paintera "$@"
    '';
    
    clean.exec = ''
      mvn clean
    '';
  };

  # https://devenv.sh/processes/
  # processes.ping.exec = "ping example.com";

  # https://devenv.sh/services/
  # services.postgres.enable = true;

  # https://devenv.sh/languages/
  # languages.rust.enable = true;

  # https://devenv.sh/pre-commit-hooks/
  pre-commit.hooks = {
    # Custom hooks can be added here
    # checkstyle.enable = true;
  };

  # https://devenv.sh/integrations/dotenv/
  dotenv.enable = true;

  # Enter shell message
  enterShell = ''
    echo "🎨 Paintera Development Environment"
    echo ""
    echo "Available commands:"
    echo "  build          - Build the project (mvn clean package -DskipTests)"
    echo "  test           - Run tests (mvn test)"
    echo "  run-mvn        - Run Paintera via Maven (mvn javafx:run)"
    echo "  run-java       - Run with all JavaFX flags applied"
    echo "  clean          - Clean build artifacts"
    echo ""
    echo "Java version: $(java -version 2>&1 | head -n 1)"
    echo "Maven version: $(mvn -version | head -n 1)"
    echo ""
  '';
}