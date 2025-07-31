{
  description = "Paintera - 3D visualization and annotation tool";

  inputs = {
    devenv-root = {
      url = "file+file:///dev/null";
      flake = false;
    };
    flake-parts.url = "github:hercules-ci/flake-parts";
    nixpkgs.url = "github:cachix/devenv-nixpkgs/rolling";
    devenv.url = "github:cachix/devenv";
    nix2container.url = "github:nlewo/nix2container";
    nix2container.inputs.nixpkgs.follows = "nixpkgs";
    mk-shell-bin.url = "github:rrbutani/nix-mk-shell-bin";
  };

  nixConfig = {
    extra-trusted-public-keys = "devenv.cachix.org-1:w1cLUi8dv3hnoSPGAuibQv+f9TZLr6cv/Hm9XgU50cw=";
    extra-substituters = "https://devenv.cachix.org";
  };

  outputs = inputs@{ flake-parts, devenv-root, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      imports = [
        inputs.devenv.flakeModule
      ];
      systems = [ "x86_64-linux" "i686-linux" "x86_64-darwin" "aarch64-linux" "aarch64-darwin" ];

      perSystem = { config, self', inputs', pkgs, system, ... }: {
        # Per-system attributes can be defined here. The self' and inputs'
        # module parameters provide easy access to attributes of the same
        # system.

        packages.paintera = pkgs.maven.buildMavenPackage rec {
          pname = "paintera";
          version = "1.10.3-SNAPSHOT";

          src = ./.;

          mvnHash = "sha256-wYli8Jkzn53MMZw0l8v/W1tiqDorqKOHO6TNUdvqr9s=";

          nativeBuildInputs = with pkgs; [
            makeWrapper
          ];

          buildInputs = with pkgs; [
            (openjdk21.override { enableJavaFX = true; })
            c-blosc
          ];

          mvnParameters = "-DskipTests";

          installPhase = ''
            mkdir -p $out/bin $out/share/java/paintera
            
            # Copy the main JAR and all dependencies
            cp target/dependency/*.jar $out/share/java/paintera/
            
            # Create classpath from all JARs
            CLASSPATH=""
            for jar in $out/share/java/paintera/*.jar; do
              if [ -z "$CLASSPATH" ]; then
                CLASSPATH="$jar"
              else
                CLASSPATH="$CLASSPATH:$jar"
              fi
            done
            
            # Create wrapper script that runs with proper classpath and JavaFX module system arguments
            makeWrapper ${pkgs.openjdk21.override { enableJavaFX = true; }}/bin/java $out/bin/paintera \
              --add-flags "-XX:MaxRAMPercentage=75" \
              --add-flags "--add-opens=javafx.base/javafx.util=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.base/javafx.event=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.base/javafx.beans.property=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.base/com.sun.javafx.binding=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.base/com.sun.javafx.event=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/javafx.scene=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/javafx.stage=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/javafx.geometry=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/javafx.animation=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/javafx.scene.input=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/javafx.scene.image=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.prism=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.javafx.geom=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.javafx.image=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.javafx.stage=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.javafx.perf=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.javafx.cursor=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.javafx.scene.traversal=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.javafx.geom.transform=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.scenario.animation=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.scenario.animation.shared=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.scenario.effect=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.javafx.sg.prism=ALL-UNNAMED" \
              --add-flags "--add-opens=javafx.graphics/com.sun.prism.paint=ALL-UNNAMED" \
              --add-flags "--add-exports=javafx.controls/com.sun.javafx.scene.control=ALL-UNNAMED" \
              --add-flags "-cp $CLASSPATH org.janelia.saalfeldlab.paintera.Paintera" \
              --prefix LD_LIBRARY_PATH : ${pkgs.c-blosc}/lib \
              --prefix DYLD_LIBRARY_PATH : ${pkgs.c-blosc}/lib
          '';
        };

        apps.paintera = {
          type = "app";
          program = "${config.packages.paintera}/bin/paintera";
        };

        apps.default = config.apps.paintera;
        packages.default = config.packages.paintera;

        devenv.shells.default = {
          name = "paintera";

          imports = [
            ./devenv.nix
          ];

          # https://devenv.sh/reference/options/
          packages = [ config.packages.default ];

          enterShell = '''';
        };

      };
      flake = {
        # The usual flake attributes can be defined here, including system-
        # agnostic ones like nixosModule and system-enumerating ones, although
        # those are more easily expressed in perSystem.

      };
    };
}
