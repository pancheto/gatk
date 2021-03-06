/*
 * Copyright (c) 2011, The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.utils.help;

import com.google.java.contract.Ensures;
import com.google.java.contract.Requires;
import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.FieldDoc;
import com.sun.javadoc.RootDoc;
import com.sun.javadoc.Tag;
import org.apache.log4j.Logger;
import org.broad.tribble.Feature;
import org.broad.tribble.bed.FullBEDFeature;
import org.broadinstitute.sting.commandline.*;
import org.broadinstitute.sting.gatk.CommandLineGATK;
import org.broadinstitute.sting.gatk.arguments.DbsnpArgumentCollection;
import org.broadinstitute.sting.gatk.refdata.tracks.FeatureManager;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.utils.classloader.JVMUtils;
import org.broadinstitute.sting.utils.collections.Pair;
import org.broadinstitute.sting.utils.exceptions.ReviewedStingException;
import org.broadinstitute.sting.utils.exceptions.StingException;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/**
 *
 */
public class GenericDocumentationHandler extends DocumentedGATKFeatureHandler {
    private static Logger logger = Logger.getLogger(GenericDocumentationHandler.class);

    /**
     * The max. length of the longest of --fullName -shortName argument name
     * before we prefer the shorter option.
     */
    private static final int MAX_DISPLAY_NAME = 30;

    /** The Class we are documenting */
    private GATKDocWorkUnit toProcess;

    @Override
    public boolean includeInDocs(ClassDoc doc) {
        try {
            Class type = HelpUtils.getClassForDoc(doc);
            boolean hidden = ! getDoclet().showHiddenFeatures() && type.isAnnotationPresent(Hidden.class);
            return ! hidden && JVMUtils.isConcrete(type);
        } catch ( ClassNotFoundException e ) {
            return false;
        }
    }


    @Override
    public String getTemplateName(ClassDoc doc) throws IOException {
        return "generic.template.html";
    }

    @Override
    public void processOne(GATKDocWorkUnit toProcessArg) {
        this.toProcess = toProcessArg;

        //System.out.printf("%s class %s%n", toProcess.group, toProcess.classDoc);
        Map<String, Object> root = new HashMap<String, Object>();

        addHighLevelBindings(root);
        addArgumentBindings(root);
        addRelatedBindings(root);

        toProcess.setHandlerContent((String)root.get("summary"), root);
    }

    /**
     * Add high-level summary information about toProcess to root, such as its
     * name, summary, description, version, etc.
     *
     * @param root
     */
    protected void addHighLevelBindings(Map<String, Object> root) {
        root.put("name", toProcess.classDoc.name());

        // Extract overrides from the doc tags.
        StringBuilder summaryBuilder = new StringBuilder();
        for(Tag tag: toProcess.classDoc.firstSentenceTags())
            summaryBuilder.append(tag.text());
        root.put("summary", summaryBuilder.toString());
        root.put("description", toProcess.classDoc.commentText().substring(summaryBuilder.toString().length()));
        root.put("timestamp", toProcess.buildTimestamp);
        root.put("version", toProcess.absoluteVersion);

        for(Tag tag: toProcess.classDoc.tags()) {
            root.put(tag.name(), tag.text());
        }
    }

    /**
     * Add bindings describing related GATK capabilites to toProcess
     * @param root
     */
    protected void addRelatedBindings(Map<String, Object> root) {
        List<Map<String, Object>> extraDocsData = new ArrayList<Map<String, Object>>();

        // add in all of the explicitly related items
        for ( final Class extraDocClass : toProcess.annotation.extraDocs() ) {
            final GATKDocWorkUnit otherUnit = getDoclet().findWorkUnitForClass(extraDocClass);
            if ( otherUnit == null )
                throw new ReviewedStingException("Requested extraDocs for class without any documentation: " + extraDocClass);
            extraDocsData.add(
                    new HashMap<String, Object>(){{
                        put("filename", otherUnit.filename);
                        put("name", otherUnit.name);}});

        }
        root.put("extradocs", extraDocsData);
    }

    /**
     * Add information about all of the arguments available to toProcess to root
     *
     * @param root
     */
    protected void addArgumentBindings(Map<String, Object> root) {
        ParsingEngine parsingEngine = createStandardGATKParsingEngine();

        Map<String, List<Map<String, Object>>> args = createArgumentMap();
        root.put("arguments", args);
        try {
            // loop over all of the arguments according to the parsing engine
            for ( final ArgumentSource argumentSource : parsingEngine.extractArgumentSources(HelpUtils.getClassForDoc(toProcess.classDoc)) ) {
                // todo -- why can you have multiple ones?
                ArgumentDefinition argDef = argumentSource.createArgumentDefinitions().get(0);
                FieldDoc fieldDoc = getFieldDoc(toProcess.classDoc, argumentSource.field.getName());
                Map<String, Object> argBindings = docForArgument(fieldDoc, argumentSource, argDef);
                if ( ! argumentSource.isHidden() || getDoclet().showHiddenFeatures() ) {
                    final String kind = docKindOfArg(argumentSource);

                    final Object value = argumentValue(toProcess.clazz, argumentSource);
                    if ( value != null )
                        argBindings.put("defaultValue", prettyPrintValueString(value));

                    args.get(kind).add(argBindings);
                    args.get("all").add(argBindings);
                }
            }

            // sort the arguments
            for (Map.Entry<String,List<Map<String, Object>>> entry : args.entrySet()) {
                entry.setValue(sortArguments(entry.getValue()));
            }
        } catch ( ClassNotFoundException e ) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Return the argument kind (required, advanced, hidden, etc) of this argumentSource
     * @param argumentSource
     * @return
     */
    @Requires("argumentSource != null")
    @Ensures("result != null")
    private String docKindOfArg(ArgumentSource argumentSource) {
        if ( argumentSource.isRequired() ) return "required";
        else if ( argumentSource.isAdvanced() ) return "advanced";
        else if ( argumentSource.isHidden() ) return "hidden";
        else if ( argumentSource.isDeprecated() ) return "depreciated";
        else return "optional";
    }

    /**
     * Attempts to determine the value of argumentSource in an instantiated version of c
     * @param c
     * @param argumentSource
     * @return value of argumentSource, or null if this isn't possible
     */
    @Requires({"c != null", "argumentSource != null"})
    private Object argumentValue(Class c, ArgumentSource argumentSource) {
        // get the value of the field
        // attempt to instantiate the class
        final Object instance = makeInstanceIfPossible(toProcess.clazz);
        if ( instance != null ) {
            final Object value = getFieldValue(instance, argumentSource.field.getName());
            if ( value != null )
                return value;

            if ( argumentSource.createsTypeDefault() ) {
                try { // handle the case where there's an implicit default
                    return argumentSource.typeDefaultDocString();
                } catch (ReviewedStingException e) {
                    ; // failed to create type default, don't worry about it
                }
            }
        }

        return null;
    }

    /**
     * Create the argument map for holding class arguments
     * @return
     */
    private Map<String, List<Map<String, Object>>> createArgumentMap() {
        Map<String, List<Map<String, Object>>> args = new HashMap<String, List<Map<String, Object>>>();
        args.put("all", new ArrayList<Map<String, Object>>());
        args.put("required", new ArrayList<Map<String, Object>>());
        args.put("optional", new ArrayList<Map<String, Object>>());
        args.put("advanced", new ArrayList<Map<String, Object>>());
        args.put("hidden", new ArrayList<Map<String, Object>>());
        args.put("depreciated", new ArrayList<Map<String, Object>>());
        return args;
    }


    /**
     * Sorts the individual argument list in unsorted according to CompareArgumentsByName
     * @param unsorted
     * @return
     */
    private List<Map<String, Object>> sortArguments(List<Map<String, Object>> unsorted) {
        Collections.sort(unsorted, new CompareArgumentsByName());
        return unsorted;
    }

    /**
     * Sort arguments by case-insensitive comparison ignoring the -- and - prefixes
     */
    private class CompareArgumentsByName implements Comparator<Map<String, Object>> {
        public int compare(Map<String, Object> x, Map<String, Object> y) {
            return elt(x).compareTo(elt(y));
        }

        private String elt(Map<String, Object> m) {
            String v = m.get("name").toString().toLowerCase();
            if ( v.startsWith("--") )
                return v.substring(2);
            else if ( v.startsWith("-") )
                return v.substring(1);
            else
                throw new RuntimeException("Expect to see arguments beginning with at least one -, but found " + v);
        }
    }

    /**
     * Utility function that finds the value of fieldName in any fields of ArgumentCollection fields in
     * instance of class c.
     *
     * @param instance the object to query for the field value
     * @param fieldName the name of the field we are looking for in instance
     * @return The value assigned to field in the ArgumentCollection, otherwise null
     */
    private Object getFieldValue(Object instance, String fieldName) {
        //
        // subtle note.  If you have a field named X that is an ArgumentCollection that
        // contains a field X as well, you need only consider fields in the argumentCollection, not
        // matching the argument itself.
        //
        // @ArgumentCollection
        // protected DbsnpArgumentCollection dbsnp = new DbsnpArgumentCollection();
        //
        for ( Field field : JVMUtils.getAllFields(instance.getClass()) ) {
            if ( field.isAnnotationPresent(ArgumentCollection.class) ) {
                //System.out.printf("Searching for %s in argument collection field %s%n", fieldName, field);
                Object fieldValue = JVMUtils.getFieldValue(field, instance);
                Object value = getFieldValue(fieldValue, fieldName);
                if ( value != null )
                    return value;
            } else if ( field.getName().equals(fieldName) ) {
                return JVMUtils.getFieldValue(field, instance);
            }
        }

        return null;
    }

    /**
     * Pretty prints value
     *
     * Assumes value != null
     * @param value
     * @return
     */
    private Object prettyPrintValueString(Object value) {
        if ( value.getClass().isArray() ) {
            Class type = value.getClass().getComponentType();
            if ( boolean.class.isAssignableFrom(type) )
                return Arrays.toString((boolean[])value);
            if ( byte.class.isAssignableFrom(type) )
                return Arrays.toString((byte[])value);
            if ( char.class.isAssignableFrom(type) )
                return Arrays.toString((char[])value);
            if ( double.class.isAssignableFrom(type) )
                return Arrays.toString((double[])value);
            if ( float.class.isAssignableFrom(type) )
                return Arrays.toString((float[])value);
            if ( int.class.isAssignableFrom(type) )
                return Arrays.toString((int[])value);
            if ( long.class.isAssignableFrom(type) )
                return Arrays.toString((long[])value);
            if ( short.class.isAssignableFrom(type) )
                return Arrays.toString((short[])value);
            if ( Object.class.isAssignableFrom(type) )
                return Arrays.toString((Object[])value);
            else
                throw new RuntimeException("Unexpected array type in prettyPrintValue.  Value was " + value + " type is " + type);
        } else if ( RodBinding.class.isAssignableFrom(value.getClass() ) ) {
            // annoying special case to handle the UnBound() constructor
            return "none";
        } else if ( value instanceof String ) {
            return value.equals("") ? "\"\"" : value;
        } else {
            return value.toString();
        }
    }

    /**
     * Attempt to instantiate class c, if possible.  Returns null if this proves impossible.
     * @param c
     * @return
     */
    private Object makeInstanceIfPossible(Class c) {
        Object instance = null;
        try {
            // don't try to make something where we will obviously fail
            if (! c.isEnum() && ! c.isAnnotation() && ! c.isAnonymousClass() &&
                    ! c.isArray() && ! c.isPrimitive() & JVMUtils.isConcrete(c) ) {
                instance = c.newInstance();
                //System.out.printf("Created object of class %s => %s%n", c, instance);
                return instance;
            } else
                return null;
        }
        catch (IllegalAccessException e ) { }
        catch (InstantiationException e ) { }
        catch (ExceptionInInitializerError e ) { }
        catch (SecurityException e ) { }
        // this last one is super dangerous, but some of these methods catch ClassNotFoundExceptions
        // and rethrow then as RuntimeExceptions
        catch (RuntimeException e) {}

        return instance;
    }


    /**
     * Create an instance of the GATK parsing engine, for argument processing with GATKDoclet
     * @return
     */
    private ParsingEngine createStandardGATKParsingEngine() {
        CommandLineProgram clp = new CommandLineGATK();
        try {
            CommandLineProgram.start(clp, new String[]{}, true);
            return clp.parser;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the javadocs associated with field name in classDoc.  Throws a
     * runtime exception if this proves impossible.
     *
     * @param classDoc
     * @param name
     * @return
     */
    private FieldDoc getFieldDoc(ClassDoc classDoc, String name) {
        return getFieldDoc(classDoc, name, true);
    }

    /**
     * Recursive helper routine to getFieldDoc()
     * @param classDoc
     * @param name
     * @param primary
     * @return
     */
    private FieldDoc getFieldDoc(ClassDoc classDoc, String name, boolean primary) {
        //System.out.printf("Looking for %s in %s%n", name, classDoc.name());
        for ( FieldDoc fieldDoc : classDoc.fields(false) ) {
            //System.out.printf("fieldDoc " + fieldDoc + " name " + fieldDoc.name());
            if ( fieldDoc.name().equals(name) )
                return fieldDoc;

            Field field = HelpUtils.getFieldForFieldDoc(fieldDoc);
            if ( field == null )
                throw new RuntimeException("Could not find the field corresponding to " + fieldDoc + ", presumably because the field is inaccessible");
            if ( field.isAnnotationPresent(ArgumentCollection.class) ) {
                ClassDoc typeDoc = getRootDoc().classNamed(fieldDoc.type().qualifiedTypeName());
                if ( typeDoc == null )
                    throw new ReviewedStingException("Tried to get javadocs for ArgumentCollection field " + fieldDoc + " but could't find the class in the RootDoc");
                else {
                    FieldDoc result = getFieldDoc(typeDoc, name, false);
                    if ( result != null )
                        return result;
                    // else keep searching
                }
            }
        }

        // if we didn't find it here, wander up to the superclass to find the field
        if ( classDoc.superclass() != null ) {
            return getFieldDoc(classDoc.superclass(), name, false);
        }

        if ( primary )
            throw new RuntimeException("No field found for expected field " + name);
        else
            return null;
    }

    /**
     * Returns a Pair of (main, synonym) names for argument with fullName s1 and
     * shortName s2.  The main is selected to be the longest of the two, provided
     * it doesn't exceed MAX_DISPLAY_NAME, in which case the shorter is taken.
     *
     * @param s1 the short argument name without -, or null if not provided
     * @param s2 the long argument name without --, or null if not provided
     * @return A pair of fully qualified names (with - or --) for the argument.  The first
     *  element is the primary display name while the second (potentially null) is a
     *  synonymous name.
     */
    Pair<String, String> displayNames(String s1, String s2) {
        s1 = s1 == null ? null : "-" + s1;
        s2 = s2 == null ? null : "--" + s2;

        if ( s1 == null ) return new Pair<String, String>(s2, null);
        if ( s2 == null ) return new Pair<String, String>(s1, null);

        String l = s1.length() > s2.length() ? s1 : s2;
        String s = s1.length() > s2.length() ? s2 : s1;

        if ( l.length() > MAX_DISPLAY_NAME )
            return new Pair<String, String>(s, l);
        else
            return new Pair<String, String>(l, s);
    }

    /**
     * Returns a human readable string that describes the Type type of a GATK argument.
     *
     * This will include parameterized types, so that Set{T} shows up as Set(T) and not
     * just Set in the docs.
     *
     * @param type
     * @return
     */
    protected String argumentTypeString(Type type) {
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType)type;
            List<String> subs = new ArrayList<String>();
            for (Type actualType: parameterizedType.getActualTypeArguments())
                subs.add(argumentTypeString(actualType));
            return argumentTypeString(((ParameterizedType)type).getRawType()) + "[" + Utils.join(",", subs) + "]";
        } else if (type instanceof GenericArrayType) {
            return  argumentTypeString(((GenericArrayType)type).getGenericComponentType()) + "[]";
        } else if (type instanceof WildcardType) {
            throw new RuntimeException("We don't support wildcards in arguments: " + type);
        } else if (type instanceof Class<?>) {
            return ((Class) type).getSimpleName();
        } else {
            throw new StingException("Unknown type: " + type);
        }
    }

    /**
     * Helper routine that returns the Feature.class required by a RodBinding,
     * either T for RodBinding{T} or List{RodBinding{T}}.  Returns null if
     * the Type doesn't fit either model.
     * @param type
     * @return
     */
    protected Class<? extends Feature> getFeatureTypeIfPossible(Type type) {
        if ( type instanceof ParameterizedType) {
            ParameterizedType paramType = (ParameterizedType)type;
            if ( RodBinding.class.isAssignableFrom((Class<?>)paramType.getRawType()) ) {
                return (Class<? extends Feature>)JVMUtils.getParameterizedTypeClass(type);
            } else {
                for ( Type paramtype : paramType.getActualTypeArguments() ) {
                    Class<? extends Feature> x = getFeatureTypeIfPossible(paramtype);
                    if ( x != null )
                        return x;
                }
            }
        }

        return null;
    }

    /**
     * High-level entry point for creating a FreeMarker map describing the GATK argument
     * source with definition def, with associated javadoc fieldDoc.
     * @param fieldDoc
     * @param source
     * @param def
     * @return a non-null Map binding argument keys with their values
     */
    protected Map<String, Object> docForArgument(FieldDoc fieldDoc, ArgumentSource source, ArgumentDefinition def) {
        Map<String, Object> root = new HashMap<String, Object>();
        Pair<String, String> names = displayNames(def.shortName, def.fullName);

        root.put("name", names.getFirst() );

        if ( names.getSecond() != null )
            root.put("synonyms", names.getSecond());

        root.put("required", def.required ? "yes" : "no");

        // type of the field
        root.put("type", argumentTypeString(source.field.getGenericType()));

        Class<? extends Feature> featureClass = getFeatureTypeIfPossible(source.field.getGenericType());
        if ( featureClass != null ) {
            // deal with the allowable types
            FeatureManager manager = new FeatureManager();
            List<String> rodTypes = new ArrayList<String>();
            for (FeatureManager.FeatureDescriptor descriptor : manager.getByFeature(featureClass) ) {
                rodTypes.add(String.format("<a href=%s>%s</a>",
                        GATKDocUtils.htmlFilenameForClass(descriptor.getCodecClass()),
                        descriptor.getName()));
            }

            root.put("rodTypes", Utils.join(", ", rodTypes));
        }

        // summary and fulltext
        root.put("summary", def.doc != null ? def.doc : "");
        root.put("fulltext", fieldDoc.commentText());

        // What are our enum options?
        if ( def.validOptions != null )
            root.put("options", docForEnumArgument(source.field.getType()));

        // general attributes
        List<String> attributes = new ArrayList<String>();
        if ( def.required ) attributes.add("required");
        if ( source.isDeprecated() ) attributes.add("depreciated");
        if ( attributes.size() > 0 )
            root.put("attributes", Utils.join(", ", attributes));

        return root;
    }

    /**
     * Helper routine that provides a FreeMarker map for an enumClass, grabbing the
     * values of the enum and their associated javadoc documentation.
     * @param enumClass
     * @return
     */
    @Requires("enumClass.isEnum()")
    private List<Map<String, Object>> docForEnumArgument(Class enumClass) {
        ClassDoc doc = this.getDoclet().getClassDocForClass(enumClass);
        if ( doc == null ) //  || ! doc.isEnum() )
            throw new RuntimeException("Tried to get docs for enum " + enumClass + " but got instead: " + doc);

        List<Map<String, Object>> bindings = new ArrayList<Map<String, Object>>();
        for (final FieldDoc field : doc.fields(false) ) {
            bindings.add(
                    new HashMap<String, Object>(){{
                        put("name", field.name());
                        put("summary", field.commentText());}});
        }

        return bindings;
    }
}
