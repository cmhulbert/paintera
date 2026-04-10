{
  description = "Paintera – New Era Painting and annotation tool";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  nixConfig = {
    extra-substituters = [ "https://paintera.cachix.org" ];
    extra-trusted-public-keys = [ "paintera.cachix.org-1:aUGNAhlQ6mCuV+YsjPNkaR+LdfHO3PtHtpZitvcK3Nw=" ];
  };

  outputs = { self, nixpkgs }:
    let
      supportedSystems = [ "x86_64-linux" "aarch64-darwin" "x86_64-darwin" ];
      forAllSystems = nixpkgs.lib.genAttrs supportedSystems;
    in
    {
      packages = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
          jdk = pkgs.zulu25.override { enableJavaFX = true; };

          runtimeLibs = pkgs.lib.makeLibraryPath (
            [ pkgs.c-blosc ]
            ++ pkgs.lib.optionals pkgs.stdenv.hostPlatform.isLinux [
              pkgs.gtk3
              pkgs.glib
              pkgs.mesa
              pkgs.libxtst
              pkgs.libxxf86vm
              pkgs.gst_all_1.gstreamer
              pkgs.gst_all_1.gst-plugins-base
            ]
          );

          libPathVar = if pkgs.stdenv.hostPlatform.isDarwin then "DYLD_LIBRARY_PATH" else "LD_LIBRARY_PATH";

          # Maven fetches platform-specific JavaFX classifier JARs, so the
          # local repo contents (and thus mvnHash) differ per OS.
          mvnHashes = {
            x86_64-linux   = "sha256-aXjy2jX0Pr/t3K+yMfDV2XesG8FUp3ZaQaoUB4tCU5A=";
            aarch64-darwin = "sha256-DP7OCp+j8DAaFCL7g+D9Gb5jeZzqZlRyqUJ3muNuF9U=";
            x86_64-darwin  = "sha256-FJXl3upHKgpz75+CKNl8x1kvK9Yiv4ghXXDvz3FvCO4=";
          };

          addOpens = builtins.concatStringsSep " " [
            "--add-opens=javafx.base/javafx.util=ALL-UNNAMED"
            "--add-opens=javafx.base/javafx.event=ALL-UNNAMED"
            "--add-opens=javafx.base/javafx.beans.property=ALL-UNNAMED"
            "--add-opens=javafx.base/com.sun.javafx.binding=ALL-UNNAMED"
            "--add-opens=javafx.base/com.sun.javafx.event=ALL-UNNAMED"
            "--add-opens=javafx.graphics/javafx.scene=ALL-UNNAMED"
            "--add-opens=javafx.graphics/javafx.stage=ALL-UNNAMED"
            "--add-opens=javafx.graphics/javafx.geometry=ALL-UNNAMED"
            "--add-opens=javafx.graphics/javafx.animation=ALL-UNNAMED"
            "--add-opens=javafx.graphics/javafx.scene.input=ALL-UNNAMED"
            "--add-opens=javafx.graphics/javafx.scene.image=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.prism=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.javafx.geom=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.javafx.image=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.javafx.stage=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.javafx.perf=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.javafx.cursor=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.javafx.scene.traversal=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.javafx.geom.transform=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.scenario.animation=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.scenario.animation.shared=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.scenario.effect=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.javafx.sg.prism=ALL-UNNAMED"
            "--add-opens=javafx.graphics/com.sun.prism.paint=ALL-UNNAMED"
            "--add-exports=javafx.controls/com.sun.javafx.scene.control=ALL-UNNAMED"
          ];
        in
        {
          default = pkgs.maven.buildMavenPackage {
            pname = "paintera";
            version = "1.13.6-SNAPSHOT";

            src = ./.;

            mvnHash = mvnHashes.${system};
            mvnJdk = jdk;
            doCheck = false;

            nativeBuildInputs = [ pkgs.makeWrapper ];

            installPhase = ''
              runHook preInstall

              mkdir -p $out/lib $out/bin
              cp target/dependency/*.jar $out/lib/

              makeWrapper ${jdk}/bin/java $out/bin/paintera \
                --prefix ${libPathVar} : "${runtimeLibs}" \
                --add-flags "-XX:MaxRAMPercentage=75" \
                --add-flags "-Djavafx.preloader=org.janelia.saalfeldlab.paintera.ui.PainteraSplashScreen" \
                --add-flags "${addOpens}" \
                --add-flags "-cp '$out/lib/*'" \
                --add-flags "org.janelia.saalfeldlab.paintera.Paintera"

              runHook postInstall
            '';

            meta = with pkgs.lib; {
              description = "New Era Painting and annotation tool";
              homepage = "https://github.com/saalfeldlab/paintera";
              license = licenses.gpl2;
              platforms = supportedSystems;
            };
          };
        });

      apps = forAllSystems (system: {
        default = {
          type = "app";
          program = "${self.packages.${system}.default}/bin/paintera";
        };
      });
    };
}
