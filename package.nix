{ pkgs, paintera-src }:

let
  jdk = pkgs.openjdk21.override { enableJavaFX = true; };
in
pkgs.maven.buildMavenPackage rec {
  pname = "paintera";
  version = "master-${paintera-src.shortRev or "unknown"}";
  
  src = paintera-src;
  mvnHash = "sha256-9pA/Ox9KLf9EzC5jq19kLOavgsAr+VaYyi9gMbNkn7U=";
  
  nativeBuildInputs = with pkgs; [ makeWrapper jdk ];
  
  buildInputs = with pkgs; [ c-blosc ];
  
  buildPhase = ''
    runHook preBuild
    mvn clean package dependency:copy-dependencies -Dmaven.test.skip=true -DskipTests
    runHook postBuild
  '';
  
  installPhase = ''
    runHook preInstall
    
    mkdir -p $out/share/java/lib
    mkdir -p $out/bin
    
    cp target/paintera-*.jar $out/share/java/paintera.jar
    cp target/dependency/*.jar $out/share/java/lib/
    
    makeWrapper ${jdk}/bin/java $out/bin/paintera \
      --add-flags "-XX:MaxRAMPercentage=80" \
      --add-flags "--add-modules ALL-SYSTEM" \
      --add-flags "--add-opens javafx.base/javafx.util=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.base/javafx.event=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.base/javafx.beans.property=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.base/com.sun.javafx.binding=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.base/com.sun.javafx.event=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/javafx.scene=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/javafx.stage=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/javafx.geometry=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/javafx.animation=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/javafx.scene.input=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/javafx.scene.image=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.prism=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.javafx.application=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.javafx.geom=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.javafx.image=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.javafx.stage=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.javafx.perf=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.javafx.cursor=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.javafx.scene.traversal=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.javafx.geom.transform=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.scenario.animation=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.scenario.animation.shared=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.scenario.effect=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.javafx.sg.prism=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.graphics/com.sun.prism.paint=ALL-UNNAMED" \
      --add-flags "--add-opens javafx.controls/javafx.scene.control=ALL-UNNAMED" \
      --add-flags "--add-exports javafx.controls/com.sun.javafx.scene.control=ALL-UNNAMED" \
      --add-flags "--add-exports javafx.graphics/com.sun.javafx.application=ALL-UNNAMED" \
      --add-flags "--add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED" \
      --add-flags "--add-exports javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED" \
      --add-flags "--add-exports javafx.graphics/com.sun.javafx.stage=ALL-UNNAMED" \
      --add-flags "--add-exports javafx.graphics/com.sun.javafx.image=ALL-UNNAMED" \
      --add-flags "--add-exports javafx.base/com.sun.javafx=ALL-UNNAMED" \
      --add-flags "-cp $out/share/java/paintera.jar:$out/share/java/lib/*" \
      --add-flags "org.janelia.saalfeldlab.paintera.Paintera" \
      --set JAVA_HOME ${jdk}/lib/openjdk \
      --prefix LD_LIBRARY_PATH : ${pkgs.lib.makeLibraryPath [ pkgs.c-blosc ]} \
      --prefix DYLD_LIBRARY_PATH : ${pkgs.lib.makeLibraryPath [ pkgs.c-blosc ]}
    
    runHook postInstall
  '';
  
  meta = with pkgs.lib; {
    description = "3D visualization and annotation tool for large electron microscopy datasets";
    homepage = "https://github.com/saalfeldlab/paintera";
    license = licenses.gpl2Plus;
    platforms = platforms.all;
    mainProgram = "paintera";
  };
}