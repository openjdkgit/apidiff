/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.codetools.showdocs;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jdk.codetools.apidiff.Log;
import jdk.codetools.apidiff.html.Content;
import jdk.codetools.apidiff.html.HtmlAttr;
import jdk.codetools.apidiff.html.HtmlTree;
import jdk.codetools.apidiff.html.RawHtml;
import jdk.codetools.apidiff.html.TagName;
import jdk.codetools.apidiff.html.Text;
import jdk.codetools.apidiff.model.APIDocs;
import jdk.codetools.apidiff.model.SerializedFormDocs;

/**
 * A utility program to display the descriptions of the primary element and
 * member elements in a page of API documentation generated by javadoc.
 *
 */
public class Main {
    /**
     * The command-line entry point.
     *
     * @param args the command-line arguments
     * @throws Exception if an error occurred while running the program.
     */
    public static void main(String... args) throws Exception {
        new Main().run(args);
    }

    enum Mode { HTML, TEXT, MIXED }
    private Mode mode = Mode.MIXED; // default
    boolean verbose = false;

    /**
     * Execute the program.
     *
     * @param args the command-line arguments
     * @throws Exception if an error occurred while running the program.
     */
    public void run(String... args) throws Exception {
        Path inDir = null;
        Path outDir = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-d")) {
                if (++i < args.length) {
                    outDir = Path.of(args[i]);
                } else {
                    throw new Exception("no arg for -d");
                }
            } else if (arg.equals("-h") || arg.equals("--html")) {
                mode = Mode.HTML;
            } else if (arg.equals("-t") || arg.equals("--text")) {
                mode = Mode.TEXT;
            } else if (arg.equals("--mixed")) {
                mode = Mode.MIXED;
            } else if (arg.equals("-v") || arg.equals("--verbose")) {
                verbose = true;
            } else if (arg.startsWith("-")) {
                throw new Exception("unknown option: " + arg);
            } else if (inDir == null) {
                inDir = Path.of(arg);
            } else {
                throw new Exception("unknown argument: " + arg);
            }
        }

        if (inDir == null) {
            throw new Exception("no input directory specified");
        }

        if (outDir == null) {
            throw new Exception("no output directory specified");
        }

        PrintWriter out = new PrintWriter(System.out) {
            @Override
            public void close() {
                flush();
            }
        };
        PrintWriter err = new PrintWriter(System.err, true){
            @Override
            public void close() {
                flush();
            }
        };

        Log log = new Log(out, err);
        try {
            showDocs(log, inDir, outDir);
        } finally {
            log.out.flush();
            log.err.flush();
        }

    }

    Map<String, SerializedFormDocs> allSerialFormDocs;

    void showDocs(Log log, Path inFile, Path outDir) throws IOException {
        if (Files.isDirectory(inFile)) {
            Path sfFile = inFile.resolve("serialized-form.html");
            if (Files.exists(sfFile)) {
                allSerialFormDocs = SerializedFormDocs.read(log, sfFile);
            }
        }

        Pattern p = Pattern.compile("(module-summary|package-summary|[A-Z].*|serialized-form)\\.html");
        Files.walkFileTree(inFile, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (p.matcher(file.getFileName().toString()).matches()) {
                    if (verbose) {
                        log.err.println("file: " + file);
                    }
                    Path relFile = file.equals(inFile) ? file.getFileName() : inFile.relativize(file);
                    String title = relFile.toString();
                    Path pathToRoot = pathToRoot(relFile);
                    Path outFile = outDir.resolve(relFile);
                    try {
                        if (file.getFileName().toString().equals("serialized-form.html")) {
                            showSerializedFormFile(log, file, outFile, "Serialized Forms", pathToRoot);
                        } else {
                            showAPIFile(log, file, outFile, title, pathToRoot);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (verbose) {
                    log.err.println("dir: " + dir);
                }
                switch (dir.getFileName().toString()) {
                    case "jquery":
                    case "resources":
                        return FileVisitResult.SKIP_SUBTREE;
                    default:
                        return FileVisitResult.CONTINUE;
                }
            }
        });

        copyResource("showDocs.css", outDir);
    }


    private void copyResource(String name, Path dir) {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            Files.createDirectories(dir);
            Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Error writing stylesheet: " + e);
        }
    }

    void showAPIFile(Log log, Path inFile, Path outFile, String title, Path pathToRoot) throws IOException {
        APIDocs apiDocs = APIDocs.read(log, inFile);

        if (verbose) {
            log.err.println("APIDocs: " + shortText(apiDocs.getDescription()));
            log.err.println("APIDocs: " + apiDocs.getMemberDescriptions().keySet());
            apiDocs.getMemberDescriptions().forEach((k, v) -> log.err.println(shortText(k) + ": " + shortText(v)));
        }

        Path stylesheet = pathToRoot.resolve("showDocs.css");
        Files.createDirectories(outFile.getParent());
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(outFile))) {
            HtmlTree head = HtmlTree.HEAD("UTF-8", title)
                    // TODO: stylesheet link should allow stylesheet name to be overridden
                    .add(HtmlTree.LINK("stylesheet", stylesheet.toString()))
                    .add(HtmlTree.META("generator", "showDocs"));

            Map<String, String> declNames = apiDocs.getDeclarationNames();
            String decl = declNames.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining(", ", "Declaration: ", ""));

            HtmlTree dl = HtmlTree.DL().setClass("api-descriptions");
            dl.add(HtmlTree.DT(Text.of("Main Description")));
            String desc = apiDocs.getDescription();
            dl.add(HtmlTree.DD(desc == null
                        ? HtmlTree.SPAN(Text.of("(not found)")).set(HtmlAttr.STYLE, "color:gray")
                        : getContent(desc)));
            TreeMap<String,String> members = new TreeMap<>(apiDocs.getMemberDescriptions());
            for (Map.Entry<String,String> e : members.entrySet()) {
                dl.add(HtmlTree.DT(Text.of(e.getKey())));
                dl.add(HtmlTree.DD(getContent(e.getValue())));
            }

            HtmlTree body = HtmlTree.BODY()
                    .add(HtmlTree.H1(Text.of(title)))
                    .add(HtmlTree.P().add(new Text(decl)))
                    .add(dl);

            if (allSerialFormDocs != null && declNames.containsKey("class")) {
                StringBuilder sb = new StringBuilder();
                String pkg = declNames.get("package");
                if (pkg != null) {
                    sb.append(pkg).append(".");
                }
                sb.append(declNames.get("class"));
                SerializedFormDocs sfDocs = allSerialFormDocs.get(sb.toString());
                if (sfDocs != null) {
                    body.add(new HtmlTree(TagName.HR).setClass("serialized-form-rule"))
                            .add(HtmlTree.H2(new Text("Serialized Form")))
                            .add(buildSerializedForm(sfDocs));
                }
            }

            HtmlTree html = new HtmlTree(TagName.HTML, head, body).set(HtmlAttr.LANG, "en_US");
            html.write(out);
        }
    }

    void showSerializedFormFile(Log log, Path inFile, Path outFile, String title, Path pathToRoot) throws IOException {
        Map<String, SerializedFormDocs> allSerialFormDocs = SerializedFormDocs.read(log, inFile);
        showSerializedFormFile(log, allSerialFormDocs, outFile, title, pathToRoot);
    }

    void showSerializedFormFile(Log log, Map<String, SerializedFormDocs> allSerialFormDocs,
                                Path outFile, String title, Path pathToRoot) throws IOException {

        if (verbose) {
            allSerialFormDocs.forEach((k, v) -> {
                List<String> list = new ArrayList<>();
                if (v.getOverview() != null) {
                    list.add("overview");
                }
                if (v.getSerialVersionUID() != null) {
                    list.add("svuid");
                }
                list.addAll(v.getFieldDescriptions().keySet());
                list.addAll(v.getMethodDescriptions().keySet());
                log.err.println(k + ": " + shortText(String.join(",", list)));
            });
        }

        Path stylesheet = pathToRoot.resolve("showDocs.css");
        Files.createDirectories(outFile.getParent());
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(outFile))) {
            HtmlTree head = HtmlTree.HEAD("UTF-8", title)
                    // TODO: stylesheet link should allow stylesheet name to be overridden
                    .add(HtmlTree.LINK("stylesheet", stylesheet.toString()))
                    .add(HtmlTree.META("generator", "showDocs"));

            HtmlTree dl = HtmlTree.DL().setClass("all-serialized-forms");
            for (Map.Entry<String, SerializedFormDocs> entry : allSerialFormDocs.entrySet()) {
                String typeName = entry.getKey();
                SerializedFormDocs sfDocs = entry.getValue();
                dl.add(HtmlTree.DT(Text.of(typeName)));
                dl.add(HtmlTree.DD(buildSerializedForm(sfDocs)));
            }
            HtmlTree body = HtmlTree.BODY()
                    .add(HtmlTree.H1(Text.of(title)))
                    .add(dl);
            HtmlTree html = new HtmlTree(TagName.HTML, head, body).set(HtmlAttr.LANG, "en_US");
            html.write(out);
        }
    }

    private HtmlTree buildSerializedForm(SerializedFormDocs sfDocs) {
        HtmlTree dl = HtmlTree.DL().setClass("serialized-form-descriptions");
        String overview = sfDocs.getOverview();
        if (overview != null) {
            dl.add(HtmlTree.DT(Text.of("Overview")))
                    .add(HtmlTree.DD(getContent(overview)));
        }
        String svuid = sfDocs.getSerialVersionUID();
        if (svuid != null) {
            dl.add(HtmlTree.DT(Text.of("SerialVersionUID")))
                    .add(HtmlTree.DD(getContent(svuid)));
        }
        sfDocs.getFieldDescriptions().forEach((f, description) -> {
            dl.add(HtmlTree.DT(Text.of(f)))
                    .add(HtmlTree.DD(getContent(description)));

        });
        sfDocs.getMethodDescriptions().forEach((m, description) -> {
            dl.add(HtmlTree.DT(Text.of(m)))
                    .add(HtmlTree.DD(getContent(description)));

        });
        return dl;
    }

    private Content getContent(String desc) {
        switch (mode) {
            case HTML:
                return HtmlTree.DIV(new RawHtml(desc)).setClass("html");

            case TEXT:
                return HtmlTree.PRE(Text.of(desc)).setClass("text");

            case MIXED:
                return HtmlTree.DIV(List.of(
                        HtmlTree.DIV(new RawHtml(desc)).setClass("html"),
                        HtmlTree.DETAILS(
                                HtmlTree.SUMMARY(Text.of("Source")),
                                HtmlTree.PRE(Text.of(desc)).setClass("text")
                        )

                ));
            default:
                throw new IllegalStateException();
        }
    }


    private Path pathToRoot(Path relFile) {
        if (relFile.getParent() == null) {
            return Path.of(".");
        } else {
            return Path.of(relFile.getParent().toString().replaceAll("[^/\\\\]+", ".."));
        }
    }

    private String shortText(String s) {
        if (s == null)
            return null;
        else if (s.length() < 10)
            return s;
        else
            return s.substring(0,10);
    }
}
