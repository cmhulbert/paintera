{
  description = "Paintera - 3D visualization and annotation tool for large electron microscopy datasets";

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
    paintera-src = {
      url = "github:saalfeldlab/paintera/master";
      flake = false;
    };
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

      perSystem = { config, self', inputs', pkgs, system, ... }: 
      let
        jdk = pkgs.openjdk21.override { enableJavaFX = true; };
        
        # Import the package definition
        paintera = pkgs.callPackage ./package.nix {
          paintera-src = inputs.paintera-src;
        };
      in {
        # Package outputs
        packages = {
          default = paintera;
          paintera = paintera;
        };

        # Apps
        apps = {
          default = {
            type = "app";
            program = "${paintera}/bin/paintera";
          };
        };

        # Enhanced devenv shell
        devenv.shells.default = {
          name = "paintera-dev";

          imports = [
            # Import the devenv.nix configuration
            ./devenv.nix
          ];

          # Additional packages beyond what's in devenv.nix
          packages = [ config.packages.default ];

          enterShell = ''
            echo "🎨 Paintera Development Environment"
            echo ""
            echo "Available commands:"
            echo "  build          - Build the project (mvn clean package -DskipTests)"
            echo "  test           - Run tests (mvn test)"
            echo "  run-mvn        - Run Paintera via Maven (mvn javafx:run)"
            echo "  run-java       - Run with all JavaFX flags applied"
            echo "  clean          - Clean build artifacts"
            echo "  paintera       - Run the packaged version"
            echo ""
            echo "Java version: $(java -version 2>&1 | head -n 1)"
            echo "Maven version: $(mvn -version | head -n 1)"
            echo ""
          '';
        };

        # Basic nix devShell for users who don't want devenv
        devShells.basic = pkgs.mkShell {
          buildInputs = with pkgs; [ jdk maven git c-blosc ];
          shellHook = ''
            echo "Basic Paintera development environment"
            echo "Use 'nix develop' for enhanced development features"
            export JAVA_HOME=${jdk}/lib/openjdk
            export LD_LIBRARY_PATH=${pkgs.c-blosc}/lib:$LD_LIBRARY_PATH
            export DYLD_LIBRARY_PATH=${pkgs.c-blosc}/lib:$DYLD_LIBRARY_PATH
          '';
        };
      };

      flake = {
        # System-agnostic flake attributes can be defined here
      };
    };
}