{ pkgs, ... }:
{
  # KKI (fork apache/incubator-kie, ligne OptaPlanner) — environnement de dev
  # reproductible (GUI-PRO-012). Remplace les binaires ad-hoc telecharges a la
  # main en scratchpad (Maven 3.9.16, Temurin JDK 21 — necessaire car le JDK 21
  # empaquete Ubuntu n'a pas lib/ct.sym, ce qui casse `javac --release`, cf
  # REQ-KKI-001) par un pin nix, reproductible sur toute machine de l'equipe.

  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk21;
    maven.enable = true;
  };

  packages = with pkgs; [
    git
    # tornadovm-installer (REQ-KKI-002) : packaging/requests/rich fournis par
    # nix (withPackages) — le python3 nu n'a pas pip par design nixpkgs, et
    # pip install a la main serait exactement le genre de binaire ad-hoc que
    # ce fichier remplace.
    (python3.withPackages (ps: with ps; [ packaging requests tqdm urllib3 wget streamlit ]))
    cmake          # TornadoVM backends natifs (glue JNI OpenCL/PTX)
    gcc
    gnumake
    ocl-icd        # loader OpenCL cote build — le runtime NVIDIA vient du
    opencl-headers # passthrough WSL (/etc/OpenCL/vendors/nvidia.icd), pas de nix ici
  ];

  env = {
    # nix ocl-icd fournit libOpenCL.so (le loader ICD) mais ne le met pas sur
    # le chemin de chargement dynamique par defaut — TornadoVM/JNI le charge
    # par dlopen, pas par RPATH. Le runtime GPU (nvidia.icd) vient du systeme
    # (passthrough WSL, /etc/OpenCL/vendors/), pas de nix : seul le loader
    # cote nix a besoin de cette aide.
    LD_LIBRARY_PATH = "${pkgs.ocl-icd}/lib";
    # Le loader nix ne trouvait pas /etc/OpenCL/vendors/nvidia.icd tout seul
    # (clGetPlatformIDs -> -1001, aucune plateforme) alors que le fichier et
    # libnvidia-opencl.so.1 sont bien presents (verifie : ldconfig -p les
    # liste). OCL_ICD_VENDORS est la variable standard du loader ocl-icd pour
    # pointer explicitement le repertoire de vendors — mecanisme documente,
    # pas un contournement.
    OCL_ICD_VENDORS = "/etc/OpenCL/vendors";
  };

  enterShell = ''
    echo "KKI devenv — $(java -version 2>&1 | head -1) · $(mvn -version 2>&1 | head -1)"
  '';
}
