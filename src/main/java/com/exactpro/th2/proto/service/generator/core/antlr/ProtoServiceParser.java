/*
 * Copyright 2020-2020 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.exactpro.th2.proto.service.generator.core.antlr;

import com.exactpro.th2.proto.service.generator.core.antlr.descriptor.MethodDescriptor;
import com.exactpro.th2.proto.service.generator.core.antlr.descriptor.ServiceDescriptor;
import com.exactpro.th2.proto.service.generator.core.antlr.descriptor.ServiceParserContext;
import com.exactpro.th2.proto.service.generator.core.antlr.descriptor.TypeDescriptor;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ProtoServiceParser {
    private static final Logger logger = LoggerFactory.getLogger(ProtoServiceParser.class);
    private static final String PROTO_EXTENSION = ".proto";

    private static final String PROTO_PACKAGE_ALIAS = "package";
    private static final String PROTO_IMPORT_ALIAS = "import";
    private static final String PROTO_OPTION_ALIAS = "option";
    private static final String PROTO_JAVA_PACKAGE_ALIAS = "java_package";
    private static final String PROTO_MSG_ALIAS = "message";
    private static final String PROTO_SERVICE_ALIAS = "service";

    private static final String GOOGLE_PROTO_PACKAGE = "google.protobuf";
    private static final String GOOGLE_PROTO_JAVA_PACKAGE = "com." + GOOGLE_PROTO_PACKAGE;


    public static List<ServiceDescriptor> getServiceDescriptors(Path protoDir) throws IOException {

        var protoFiles = loadProtoFiles(protoDir);
        ServiceParserContext context = new ServiceParserContext();
        context.addAutoReplacedPackage(GOOGLE_PROTO_PACKAGE, GOOGLE_PROTO_JAVA_PACKAGE);

        for (var protoFile : protoFiles) {
            Path fileName = protoFile.getFileName();
            logger.info("Parsing '{}' file", fileName);

            try (FileInputStream inputStream = new FileInputStream(protoFile.toFile())) {
                parseInputStream(inputStream, fileName.toString(), context);
            }
        }

        return context.getServiceForGeneration(protoFiles.stream().map(it -> it.getFileName().toString()).collect(Collectors.toList()));
    }

    private static void parseInputStream(InputStream inputStream, String fileName, ServiceParserContext context) throws IOException {
        Protobuf3Lexer lexer = new Protobuf3Lexer(new ANTLRInputStream(inputStream));
        Protobuf3Parser parser = new Protobuf3Parser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        Protobuf3Parser.ProtoContext protoTree = parser.proto();

        // StringBuilder for apply reference behavior
        String javaPackageName = null;

        String packageName = null;

        List<String> comments = new ArrayList<>();

        for (var child : protoTree.children) {

            extractImport(child, context);

            var tmpPackageName = extractPackage(child);
            if (tmpPackageName != null) {
                packageName = tmpPackageName;
            }


            var tmpJavaPackageName = extractJavaPackage(child);
            if (tmpJavaPackageName != null) {
                javaPackageName = tmpJavaPackageName;
            }

            extractComment(child, comments);

            extractService(child, javaPackageName, comments, service -> context.addService(fileName, service));

            String finalPackageName = packageName;
            String finalJavaPackageName = javaPackageName;
            extractMessage(child, msg -> context.addType(TypeDescriptor.builder().name(msg).packageName(finalPackageName).build(), finalJavaPackageName, msg));
        }
    }

    private static void extractImport(ParseTree child, ServiceParserContext context) {
        if (child.getChildCount() == 3 && child.getChild(0).getText().equals(PROTO_IMPORT_ALIAS)) {
            var resourseName = child.getChild(1).getText();
            resourseName = resourseName.substring(1, resourseName.length() - 1);

            if (!extractPackageName(resourseName, '/').replace('/', '.').equals(GOOGLE_PROTO_PACKAGE)) {
                try {
                    URL resource = Thread.currentThread().getContextClassLoader().getResource(resourseName);
                    if (resource != null) {
                        String fileName = Path.of(resource.getFile()).getFileName().toString();
                        parseInputStream(resource.openStream(), fileName, context);
                    } else {
                        logger.warn("Can not find resource with name = {}", resourseName);
                    }
                } catch (Exception e) {
                    logger.warn("Can not parse resource with name = {}",  resourseName);
                }
            }
        }
    }

    private static String extractPackage(ParseTree node) {
        if (node.getChildCount() == 3 && node.getChild(0).getText().equals(PROTO_PACKAGE_ALIAS)) {
                return node.getChild(1).getText();
        }
        return null;
    }

    private static List<Path> loadProtoFiles(Path protoDir) throws IOException {
        Objects.requireNonNull(protoDir, "Proto files directory path cannot be null!");

        if (!protoDir.toFile().exists()) {
            throw new IOException("Provided directory with proto files does not exist: " + protoDir);
        }

        if (Files.isRegularFile(protoDir)) {
            throw new IOException("You must provide path to directory with proto files, not to a single file: " + protoDir);
        }

        var protoFiles = Files.walk(protoDir)
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(PROTO_EXTENSION))
                .collect(Collectors.toList());

        if (protoFiles.isEmpty()) {
            throw new RuntimeException("No valid proto file was found in directory: " + protoDir);
        }

        logger.info("{} proto files were found in directory {}", protoFiles.size(), protoDir);

        return protoFiles;
    }

    private static void checkExistence(String msgName, String packageName) {
        if (Objects.isNull(packageName)) {
            throw new IllegalStateException(String.format("Message<%s> definition " +
                    "not found in provided proto files", msgName));
        }
    }

    private static void extractComment(ParseTree node, List<String> comments) {
        //FIXME antlr not recognized comments properly
        if (isChildless(node) && isComment(node)) {
            comments.add(extractCommentText(node));
        }
    }

    private static String extractJavaPackage(ParseTree node) {
        if (node.getChildCount() > 3) {
            var option = node.getChild(0).getText();
            var optionName = node.getChild(1).getText();
            var value = node.getChild(3).getText();

            if (option.equals(PROTO_OPTION_ALIAS) && optionName.equals(PROTO_JAVA_PACKAGE_ALIAS)) {
                return value.replace("\"", "").replace("'", "");
            }
        }
        return null;
    }

    private static void extractService(
            ParseTree node,
            String packageName,
            List<String> comments,
            Consumer<ServiceDescriptor> serviceConsumer
    ) {
        extractEntity(node, PROTO_SERVICE_ALIAS, (serviceName, entityNode) -> {

            var serviceDesc = ServiceDescriptor.builder()
                    .name(serviceName)
                    .packageName(packageName)
                    .methods(getMethodDescriptors(entityNode))
                    .comments(new ArrayList<>(comments))
                    .annotations(new ArrayList<>())
                    .build();

            comments.clear();

            serviceConsumer.accept(serviceDesc);
        });
    }

    private static void extractMessage(
            ParseTree node,
            Consumer<String> messageConsumer) {
        extractEntity(node, PROTO_MSG_ALIAS, (msgName, entityNode) -> messageConsumer.accept(msgName));
    }

    private static void extractEntity(
            ParseTree node,
            String targetEntity,
            BiConsumer<String, ParseTree> entityConsumer
    ) {
        if (node.getChildCount() > 0) {

            var potentialEntity = node.getChild(0);

            if (potentialEntity.getChildCount() > 0) {

                var option = potentialEntity.getChild(0).getText();

                if (option.equals(targetEntity)) {
                    var entityName = potentialEntity.getChild(1).getText();
                    entityConsumer.accept(entityName, potentialEntity);
                }
            }
        }
    }

    private static List<MethodDescriptor> getMethodDescriptors(ParseTree serviceNode) {

        var startRpcDeclarationIndex = 3;

        var methodNameIndex = 1;
        var methodRequestTypeIndex = 3;
        var methodResponseTypeIndex = 7;

        List<String> comments = new ArrayList<>();
        List<MethodDescriptor> methodDescriptors = new ArrayList<>();

        for (int i = startRpcDeclarationIndex; i < serviceNode.getChildCount(); i++) {
            var methodNode = serviceNode.getChild(i);

            if (isChildless(methodNode)) {
                if (isComment(methodNode)) {
                    comments.add(extractCommentText(methodNode));
                }
                continue;
            }


            var methodName = methodNode.getChild(methodNameIndex).getText();
            var methodRequestType = methodNode.getChild(methodRequestTypeIndex).getText();
            var methodResponseType = methodNode.getChild(methodResponseTypeIndex).getText();


            var rqTypeDesc = createType(methodRequestType);
            var respTypeDesc = createType(methodResponseType);

            var methodDesc = MethodDescriptor.builder()
                    .comments(new ArrayList<>(comments))
                    .name(methodName)
                    .responseType(respTypeDesc)
                    .requestTypes(new ArrayList<>(List.of(rqTypeDesc)))
                    .build();

            comments.clear();

            methodDescriptors.add(methodDesc);
        }

        return methodDescriptors;
    }

    private static boolean isChildless(ParseTree node) {
        return node.getChildCount() == 0;
    }

    private static boolean isComment(ParseTree node) {
        var stringNode = node.toString().strip();
        return stringNode.startsWith("/**") && stringNode.endsWith("*/")
                || stringNode.startsWith("/*") && stringNode.endsWith("*/")
                || stringNode.startsWith("//");
    }

    private static String extractCommentText(ParseTree commentNode) {
        return commentNode.toString().replace("/**", "")
                .replace("/*", "")
                .replace("*/", "")
                .replace("//", "")
                .strip();
    }

    private static TypeDescriptor createType(String fullName) {
        int endPackageIndex = fullName.lastIndexOf('.');
        var builder = TypeDescriptor.builder();
        if (endPackageIndex > 0) {
            builder.packageName(fullName.substring(0, endPackageIndex)).name(fullName.substring(endPackageIndex + 1)).build();
        } else {
            builder.name(fullName);
        }
        return builder.build();
    }

    private static String extractPackageName(String fullName, char delimeter) {
        int endPackageIndex = fullName.lastIndexOf(delimeter);
        return endPackageIndex > 0 ? fullName.substring(0, endPackageIndex) : null;
    }
}
