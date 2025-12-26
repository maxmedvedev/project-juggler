class IdeaJuggler < Formula
  desc "CLI tool that manages separate IntelliJ IDEA instances per project"
  homepage "https://github.com/YOUR_USERNAME/idea-juggler"
  url "https://github.com/YOUR_USERNAME/idea-juggler/releases/download/v1.0.0/idea-juggler-1.0.0.tar.gz"
  sha256 "PUT_CHECKSUM_HERE"
  license "Apache-2.0"  # Update with actual license

  depends_on "openjdk@17"

  def install
    # Install JARs to libexec (private to formula)
    libexec.install Dir["libexec/*"]

    # Create wrapper script in bin
    (bin/"idea-juggler").write <<~EOS
      #!/bin/bash
      export JAVA_HOME="#{Formula["openjdk@17"].opt_prefix}/libexec/openjdk.jdk/Contents/Home"
      exec "${JAVA_HOME}/bin/java" -cp "#{libexec}/*" com.ideajuggler.MainKt "$@"
    EOS

    chmod 0755, bin/"idea-juggler"
  end

  test do
    output = shell_output("#{bin}/idea-juggler --help")
    assert_match "idea-juggler", output.downcase
  end
end
