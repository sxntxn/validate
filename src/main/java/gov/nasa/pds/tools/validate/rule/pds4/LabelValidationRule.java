// Copyright 2006-2018, by the California Institute of Technology.
// ALL RIGHTS RESERVED. United States Government Sponsorship acknowledged.
// Any commercial use must be negotiated with the Office of Technology Transfer
// at the California Institute of Technology.
//
// This software is subject to U. S. export control laws and regulations
// (22 C.F.R. 120-130 and 15 C.F.R. 730-774). To the extent that the software
// is subject to U.S. export control laws and regulations, the recipient has
// the responsibility to obtain export licenses or other export authority as
// may be required before exporting such information to foreign countries or
// providing access to foreign nationals.
//
// $Id$
package gov.nasa.pds.tools.validate.rule.pds4;

import gov.nasa.pds.tools.label.CachedEntityResolver;
import gov.nasa.pds.tools.label.ExceptionType;
import gov.nasa.pds.tools.label.LabelValidator;
import gov.nasa.pds.tools.label.MissingLabelSchemaException;
import gov.nasa.pds.tools.label.SchematronTransformer;
import gov.nasa.pds.tools.label.XMLCatalogResolver;
import gov.nasa.pds.tools.util.Utility;
import gov.nasa.pds.tools.util.XMLExtractor;
import gov.nasa.pds.tools.validate.ProblemContainer;
import gov.nasa.pds.tools.validate.ProblemDefinition;
import gov.nasa.pds.tools.validate.ProblemType;
import gov.nasa.pds.tools.validate.Target;
import gov.nasa.pds.tools.validate.ValidationProblem;
import gov.nasa.pds.tools.validate.ValidationResourceManager;
import gov.nasa.pds.tools.validate.rule.AbstractValidationRule;
import gov.nasa.pds.tools.validate.rule.FileAreaExtractor;
import gov.nasa.pds.tools.validate.rule.GenericProblems;
import gov.nasa.pds.tools.validate.rule.ValidationTest;
import gov.nasa.pds.validate.constants.Constants;
import net.sf.saxon.tree.tiny.TinyNodeImpl;
import net.sf.saxon.trans.XPathException;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPathExpressionException;

import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.ls.LSInput;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Implements a validation chain that validates PDS4 bundles. It is applicable
 * if there is a bundle label in the root directory.
 */
public class LabelValidationRule extends AbstractValidationRule {

    private static final Logger LOG = LoggerFactory.getLogger(LabelValidationRule.class);
	private static final Pattern LABEL_PATTERN = Pattern.compile(".*\\.xml", 
	    Pattern.CASE_INSENSITIVE);

	private static final String XML_SUFFIX = ".xml";
	
	private SchemaValidator schemaValidator;
	private SchematronTransformer schematronTransformer;
  private Map<URL, ProblemContainer> labelSchemaResults;
  private Map<URL, ProblemContainer> labelSchematronResults;
  private Map<URL, Transformer> labelSchematrons;
  private XMLExtractor extractor;

  private static Object lock = new Object();

  public LabelValidationRule() throws TransformerConfigurationException {
    schemaValidator = new SchemaValidator();
    schematronTransformer = new SchematronTransformer();
    labelSchemaResults = new HashMap<URL, ProblemContainer>();
    labelSchematronResults = new HashMap<URL, ProblemContainer>();
    labelSchematrons = new HashMap<URL, Transformer>();
    extractor = null;
  }
  
	@Override
	public boolean isApplicable(String location) {
    if (Utility.isDir(location)) {
      return false;
    } else {
      return true;
    }
	}

	/**
	 * Implements a rule that checks the label file extension.
	 */
	@ValidationTest
	public void checkLabelExtension() {
	  if (!FilenameUtils.getName(getTarget().toString()).endsWith(XML_SUFFIX)) {
		  reportError(PDS4Problems.INVALID_LABEL_EXTENSION, getTarget(), -1, -1);
    }
	}

    private void flagNonExistentFile(URL target) throws IOException {
      LOG.debug("flagNonExistentFile: target {}",target);
      // Do a sanity check on existence of the label and flag it.
      try {
        if (!new File(target.toURI()).exists()) {
            // Option 1: Throw the exception.
            // Throwing the exception will cause the callee of this function to report the error as "Uncaught exception".
            String message = "Label " + target.toString() + " is not found";
            LOG.error(message);

            throw new IOException(message);

            // Option 2: Report the error here.
            //reportError(GenericProblems.UNCAUGHT_EXCEPTION, target, -1, -1,
            //        message);
        }
      } catch (Exception e) {
          throw new IOException(e.getMessage());
      }
    }

    private void flagBadFilename(URL target) {
        // It is possible that the file name violate naming rules.  Check them here.  If there are problems,
        // add each problem to the listener in this class.
        //
        // Example: 3juno_lwr01896_ines%20fits%20headers.pdfa.xml contains spaces between the characters.
        //
        // Note that the class FileAndDirectoryNamingChecker extends FileAndDirectoryNamingRule but re-implement
        // with new function checkFileAndDirectoryNamingWithChecker() to not call reportError() but to return a list of ValidationProblem.

        FileAndDirectoryNamingChecker FileAndDirectoryNamingChecker = new FileAndDirectoryNamingChecker();
        List<Target> list = new ArrayList<Target>();
        list.add(new Target(target,false)); // Make a list of just one name.
        List<ValidationProblem> validationProblems = FileAndDirectoryNamingChecker.checkFileAndDirectoryNamingWithChecker(list);
        LOG.debug("flagBadFilename:target {}",target);
        LOG.debug("flagBadFilename:validationProblems.size() {}",validationProblems.size());
        if (validationProblems.size() > 0) {
            for (ValidationProblem problem : validationProblems) {
                problem.setSource(target.toString()); // Because all problems with the filename are for target, set the source with target.
                getListener().addProblem(problem);    // Add the problem of the file name to this listener.
                LOG.debug("flagBadFilename:addProblem(problem) {},{}",problem,target.toString());
            }
        }
    }

	/**
	 * Parses the label and records any errors resulting from the parse,
	 * including schema and schematron errors.
	 */
	@ValidationTest
	public void validateLabel() {
      long t0 = System.currentTimeMillis();
      boolean labelIsValidFlag = false;  // Explicit flag to indicate if target is valid or not.  Value is set to true if parsed successfully.
      URL target = getTarget();
      String targetFileName = target.toString().substring(target.toString().lastIndexOf("/") + 1);
      ProblemProcessor processor = new ProblemProcessor(
              getListener(), target);

      LabelValidator validator =
              ValidationResourceManager.INSTANCE.getResource(LabelValidator.class);

      LOG.debug("validateLabel:target,targetFileName {},{}",target,targetFileName);

      try {
        // Do a sanity check on existence of the label.
	    this.flagNonExistentFile(target);

        // Do a sanity check on bad file name.
	    this.flagBadFilename(target);

        // Also check any file names referred to in this label.
        try {
            FileAreaExtractor fileAreaExtractor = new FileAreaExtractor();
            fileAreaExtractor.findAndFlagBadFilenames(target,getListener());
        } catch (Exception ignore) {
            LOG.error("Function findAndFlagBadFilenames() failed for target {}",target);
        }

        Document document = null;
        boolean pass = true;
        ProblemContainer problemContainer = new ProblemContainer();
        if (getContext().getCatalogResolver() != null ||
                getContext().isForceLabelSchemaValidation()) {
          boolean hasValidSchemas = false;
          Map<String, Transformer> labelSchematrons = null;
          synchronized (lock) {
            // Validate the label's schema and schematron first before doing
            // label validation
            hasValidSchemas = validateLabelSchemas(target,
                    problemContainer, getContext().getCatalogResolver());

            labelSchematrons = validateLabelSchematrons(
                    target, problemContainer, getContext().getCatalogResolver());
          }

            // https://github.com/NASA-PDS/validate/issues/17
            // Important note:  Any errors found in the above two functions:
            //
            //     validateLabelSchemas(),
            //    `validateLabelSchematrons()
            //
            // would have been reported already to the listener.  There is no need to keep them around
            // because the same errors would be added again later in this function.
            // The errors need to be cleared either by the object problemContainer performing an
            // explicit clear function or by creating a new ProblemContainer object.
            //
            // Option 1: problemContainer.clear()  [Chosen in this function]
            // Option 2: problemContainer = new ProblemContainer()

            // Option 1: problemContainer.clear() [Chosen in this function]
            problemContainer.clear();

            LOG.debug("validateLabel:problemContainer.clear() called");
            LOG.debug("validateLabel:target,hasValidSchemas,labelSchematrons.size() {},{},{}",target,hasValidSchemas,labelSchematrons.size());

            if (hasValidSchemas && !labelSchematrons.isEmpty()) {
              CachedEntityResolver resolver = new CachedEntityResolver();
              resolver.addCachedEntities(schemaValidator.getCachedLSResolver()
                      .getCachedEntities());
              validator.setCachedEntityResolver(resolver);
              validator.setCachedLSResourceResolver(
                      schemaValidator.getCachedLSResolver());
              validator.setLabelSchematrons(labelSchematrons);
              if (getContext().isForceLabelSchemaValidation()) {
                try {
                  schemaValidator.setExternalLocations(
                          getExtractor(target).getSchemaLocation());
                } catch (Exception ignore) {
                  //Should not throw an exception
                }
              }
            } else {
              // Print any label problems that occurred during schema and schematron
              // validation.
              if (problemContainer.getProblems().size() != 0) {
                for (ValidationProblem problem : problemContainer.getProblems()) {
                  problem.setSource(target.toString());
                  getListener().addProblem(problem);
                  LOG.debug("validateLabel:addProblem(problem) {}",problem);
                }
              }
              pass = false;
            }
        }
        LOG.debug("validateLabel:target,pass {},{}",target,pass);
        if (pass) {
          getListener().addLocation(target.toString());
          LOG.debug("validateLabel:afor:target {}",target);
          document = validator.parseAndValidate(processor, target);
        }
        LOG.debug("validateLabel:target,document {},{}",target,document);
        if (document != null) {
          getContext().put(PDS4Context.LABEL_DOCUMENT, document);
          labelIsValidFlag = true;  // A non-null document signified that the label is valid.
        }
      } catch (SAXException | IOException | ParserConfigurationException
              | TransformerException | MissingLabelSchemaException e) {
        if (e instanceof XPathException) {
          XPathException xe = (XPathException) e;
          if (!xe.hasBeenReported()) {
            reportError(GenericProblems.UNCAUGHT_EXCEPTION, target, -1, -1,
                    e.getMessage());
          }
        } else {
          // Don't need to report SAXParseException messages as they have already
          // been reported by the LabelValidator's error handler
          if (!(e instanceof SAXParseException)) {
            reportError(GenericProblems.UNCAUGHT_EXCEPTION, target, -1, -1,
                    e.getMessage());
          }
        }
      }
      long t1 = System.currentTimeMillis();

      if (isDebugLogLevel()) {
        System.out.println("\nDEBUG  [" + ProblemType.TIMING_METRICS.getKey() + "]  " + System.currentTimeMillis() + " :: " + targetFileName + " :: validateLabel() in " + (t1 - t0) + " ms\n");
      }
      LOG.debug("validateLabel:target,labelIsValidFlag {},{}",target,labelIsValidFlag);
	}
	
  private boolean validateLabelSchemas(URL label,
      ProblemContainer labelProblems, XMLCatalogResolver resolver) {
    boolean passFlag = true;
    List<StreamSource> sources = new ArrayList<StreamSource>();
    Map<String, URL> schemaLocations = new LinkedHashMap<String, URL>();
    this.schemaValidator.setCatalogResolver(resolver);
    LOG.debug("validateLabelSchemas: label {}",label);

    try {
      schemaLocations = getSchemaLocations(label);  
      LOG.debug("validateLabelSchemas: schemaLocations,schemaLocations.size() {},{}",schemaLocations,schemaLocations.size());
    } catch (Exception e) {
      labelProblems.addProblem(new ValidationProblem(
          new ProblemDefinition(ExceptionType.FATAL,
              ProblemType.SCHEMA_ERROR,
              e.getMessage()), 
          label));
      LOG.debug("validateLabelSchemas: passFlag {}",false);
      return false;
    }
    
    LOG.debug("validateLabelSchemas: label,labelProblems.getProblemCount() {},{}",label,labelProblems.getProblemCount());
    LOG.debug("validateLabelSchemas: schemaLocations.entrySet().size {}",schemaLocations.entrySet().size());
    // Loop through all the schemaLocations from the label and resolve them
    for (Map.Entry<String, URL> schemaLocation : schemaLocations.entrySet()) {
      URL schemaUrl = schemaLocation.getValue();
      LOG.debug("validateLabelSchemas: schemaUrl {}",schemaUrl);
      ProblemContainer container = new ProblemContainer();
      boolean resolvableUrl = true;
      if (resolver != null) {
        String resolvedUrl = null;
        try {
          resolvedUrl = resolver.resolveSchema(schemaLocation.getKey(), 
              schemaUrl.toString(), label.toString());

          if (resolvedUrl != null) {
            schemaUrl = new URL(resolvedUrl);
          } else {
            labelProblems.addProblem(new ValidationProblem(
                new ProblemDefinition(ExceptionType.ERROR,
                    ProblemType.CATALOG_UNRESOLVABLE_SCHEMA,
                    "Could not resolve schema '"
                    + schemaLocation.getValue().toString()
                    + "' through the catalog"), 
                label));
            resolvableUrl = false;
          }
        } catch (IOException io) {
          labelProblems.addProblem(new ValidationProblem(
              new ProblemDefinition(ExceptionType.ERROR,
                  ProblemType.CATALOG_UNRESOLVABLE_SCHEMA,
                  "Error while trying to resolve schema '"
                  + schemaLocation.getValue().toString()
                  + "' through the catalog: " + io.getMessage()), 
              label));
          resolvableUrl = false;
        }
      }
      
      LOG.debug("validateLabelSchemas: schemaUrl,resolvableUrl {},{}",schemaUrl,resolvableUrl);
      // If we found the schema, let's read it into memory
      if (resolvableUrl) {
        schemaValidator.getCachedLSResolver().setProblemHandler(container);
        LSInput input = schemaValidator.getCachedLSResolver()
            .resolveResource("", "", "", schemaUrl.toString(), schemaUrl.toString());
        boolean addSource = true;
        if (container.getProblems().size() != 0) {
          try {
            for (ValidationProblem le : container.getProblems()) {
              le.setSource(label.toURI().toString());
              getListener().addProblem(le);
            }
            if (container.hasError() || container.hasFatal()) {
              passFlag = false;
              addSource = false;
            }
          } catch (URISyntaxException u) {
            labelProblems.addProblem(new ValidationProblem(
                new ProblemDefinition(ExceptionType.FATAL,
                    ProblemType.SCHEMA_ERROR,
                    "URI syntax exception occurred for schema '"
                        + schemaUrl.toString() + "': " + u.getMessage()),
                label));
          }
        }
        
        // Load the sources
        if (addSource) {
          StreamSource streamSource = new StreamSource(
              input.getByteStream());
          streamSource.setSystemId(schemaUrl.toString());
          sources.add(streamSource);
        }
      } else {
        passFlag = false;
      }
    }
    
    LOG.debug("validateLabelSchemas: afor:validate schema:passFlag {}",passFlag);
    if (passFlag) {
      // Now let's loop through the schemas themselves, and validate them
      for (StreamSource source : sources) {
        try {
          URL schemaUrl = null;
          try {
            schemaUrl = new URL(source.getSystemId());
          } catch(MalformedURLException ignore) {
            //Should never throw an exception
          }
          LOG.debug("validateLabelSchemas: schemaUrl {}",schemaUrl);
          ProblemContainer container = new ProblemContainer();
          if (labelSchemaResults.containsKey(schemaUrl)) {
            container = labelSchemaResults.get(schemaUrl);
            if (container.getProblems().size() != 0) {
              for (ValidationProblem le : container.getProblems()) {
                le.setSource(label.toURI().toString());
                getListener().addProblem(le);
              }
              if (container.hasError() || container.hasFatal()) {
                passFlag = false;
              }
            }
          } else {
            try {
              // Before validating we need to load all schemas
              
              // Validate the schema source
              container = schemaValidator.validate(source);
              LOG.debug("validateLabelSchemas: schemaUrl,passFlag {},{}",schemaUrl,passFlag);
            } catch (Exception e) {
              container.addProblem(new ValidationProblem(
                  new ProblemDefinition(ExceptionType.ERROR,
                      ProblemType.SCHEMA_ERROR,
                      "Error reading schema: " + e.getMessage()),
                  schemaUrl));
            }
            if (container.getProblems().size() != 0) {
              for (ValidationProblem le : container.getProblems()) {
                le.setSource(label.toURI().toString());
                getListener().addProblem(le);
              }
              if (container.hasError() || container.hasFatal()) {
                passFlag = false;
              }
            }
            labelSchemaResults.put(schemaUrl, container);
          }
        } catch (URISyntaxException u) {
          labelProblems.addProblem(new ValidationProblem(
              new ProblemDefinition(ExceptionType.FATAL,
                  ProblemType.SCHEMA_ERROR,
                  "URI syntax exception occurred for schema '"
                      + source.getSystemId() + "': " + u.getMessage()),
              label));
        }
      }
    }
    LOG.debug("validateLabelSchemas: after:validate schema:passFlag {}",passFlag);
    LOG.debug("validateLabelSchemas: label,passFlag {},{}",label,passFlag);
    return passFlag;
  }

  private Map<String, Transformer> validateLabelSchematrons(URL label,
      ProblemContainer labelProblems, XMLCatalogResolver resolver) {
    boolean passFlag = true;
    Map<String, Transformer> results = new HashMap<String, Transformer>();
    List<URL> schematronRefs = new ArrayList<URL>();
    try {
      schematronRefs = getSchematrons(label, labelProblems);
      LOG.debug("validateLabelSchematrons:labelProblems.getWarningCount() {}",labelProblems.getWarningCount());
      if (labelProblems.getProblems().size() != 0) {
        for (ValidationProblem le : labelProblems.getProblems()) {
          getListener().addProblem(le);
          LOG.debug("validateLabelSchematrons:addProblem(le) {}",le);
        }
        passFlag = false;
      }
    } catch (Exception e) {
      labelProblems.addProblem(new ValidationProblem(
          new ProblemDefinition(ExceptionType.ERROR,
              ProblemType.SCHEMATRON_ERROR,
              e.getMessage()), 
          label));
      passFlag = false;
    }
    
    LOG.debug("validateLabelSchematrons:schematronRefs.size() {}",schematronRefs.size());
    //Now validate the schematrons
    for (URL schematronRef : schematronRefs) {
      try {
        ProblemContainer container = null;
        boolean resolvableUrl = true;
        LOG.debug("validateLabelSchematrons:schematronRef,resolver {},{}",schematronRef,resolver);
        if (resolver != null) {
          String resolvedUrl = null;
          try {
            String absoluteUrl = Utility.makeAbsolute(
                Utility.getParent(label).toString(),
                schematronRef.toString());
            resolvedUrl = resolver.resolveSchematron(absoluteUrl);
            LOG.debug("validateLabelSchematrons:resolvedUrl {}",resolvedUrl);
            if (resolvedUrl != null) {
              schematronRef = new URL(resolvedUrl);
            } else {
              labelProblems.addProblem(new ValidationProblem(
                  new ProblemDefinition(ExceptionType.ERROR,
                      ProblemType.CATALOG_UNRESOLVABLE_SCHEMATRON,
                      "Could not resolve schematron '"
                      + schematronRef.toString() + "' through the catalog."), 
                      label));
              resolvableUrl = false;
            }
          } catch (IOException io) {
            labelProblems.addProblem(new ValidationProblem(
                new ProblemDefinition(ExceptionType.ERROR,
                    ProblemType.CATALOG_UNRESOLVABLE_SCHEMATRON,
                    "Error while trying to resolve schematron '"
                    + schematronRef.toString() + "' through the catalog: "
                    + io.getMessage()), 
                    label));
            resolvableUrl = false;
          }
        }
        LOG.debug("validateLabelSchematrons:schematronRef,resolvableUrl {},{}",schematronRef,resolvableUrl);
        if (resolvableUrl) {
          if (labelSchematrons.containsKey(schematronRef)) {
            container = labelSchematronResults.get(schematronRef);
            if (container.getProblems().size() != 0) {
              for (ValidationProblem le : container.getProblems()) {
                le.setSource(label.toURI().toString());
                getListener().addProblem(le);
              }
              if (container.hasError() || container.hasFatal()) {
                passFlag = false;
              }
            } else {
              results.put(schematronRef.toString(),
                  labelSchematrons.get(schematronRef));
            }
          } else {
            container = new ProblemContainer();
            try {
              Transformer transformer = schematronTransformer.transform(
                  schematronRef, container);
              labelSchematrons.put(schematronRef, transformer);
              results.put(schematronRef.toString(), transformer);
            } catch (TransformerException te) {
              //Ignore as the listener handles the exceptions and puts it into
              //the container
            } catch (Exception e) {
              container.addProblem(new ValidationProblem(
                  new ProblemDefinition(ExceptionType.FATAL,
                      ProblemType.SCHEMATRON_ERROR,
                      "Error occurred while attempting to read schematron: "
                          + e.getMessage()),
                  schematronRef));
            }
            if (container.getProblems().size() != 0) {
              for (ValidationProblem le : container.getProblems()) {
                le.setSource(label.toURI().toString());
                getListener().addProblem(le);
              }
              if (container.hasError() || container.hasFatal()) {
                passFlag = false;
              }
            }
            labelSchematronResults.put(schematronRef, container);
          }
        } else {
          passFlag = false;
        }
      } catch (URISyntaxException u) {
        labelProblems.addProblem(new ValidationProblem(
            new ProblemDefinition(ExceptionType.FATAL,
                ProblemType.SCHEMATRON_ERROR,
                "URI syntax exception occurred for schematron '"
                    + schematronRef.toString() + "': " + u.getMessage()),
            label));
      }
    }
    if (!passFlag) {
      results.clear();
    }
    LOG.debug("validateLabelSchematrons:label,labelProblems.getProblemCount() {},{}",label,labelProblems.getProblemCount());
    return results;
  }
  
  private Map<String, URL> getSchemaLocations(URL label) throws Exception {
    Map<String, URL> schemaLocations = new LinkedHashMap<String, URL>();
    String value = "";
    try {
      XMLExtractor extractor = getExtractor(label);
      value = extractor.getSchemaLocation();
    } catch (Exception e) {
      LOG.error("getSchemaLocations:Error occurred while attempting to find schemas using the XPath '" + XMLExtractor.SCHEMA_LOCATION_XPATH + "': " + e.getMessage());
      throw new Exception(
          "Error occurred while attempting to find schemas using the XPath '"
          + XMLExtractor.SCHEMA_LOCATION_XPATH + "': " + e.getMessage());
    }
    if (value == null || value.isEmpty()) {
      LOG.error("getSchemaLocations:No schema(s) found in the label.");
      throw new Exception("No schema(s) found in the label.");
    } else {
      StringTokenizer tokenizer = new StringTokenizer(value);
      if ((tokenizer.countTokens() % 2) != 0) {
        LOG.error("getSchemaLocations:schemaLocation value does not appear to have matching sets of " + "namespaces to uris: '" + schemaLocations + "'");
        throw new Exception(
            "schemaLocation value does not appear to have matching sets of "
            + "namespaces to uris: '" + schemaLocations + "'");
      } else {
        // While loop that will grab the schema URIs
        while (tokenizer.hasMoreTokens()) {
          // First token assumed to be the namespace
          String namespace = tokenizer.nextToken();
          // Second token assumed to be the URI
          String uri = tokenizer.nextToken();
          URL schemaUrl = null;
          try {
            schemaUrl = new URL(uri);
          } catch (MalformedURLException mu) {
            // The schema specification value does not appear to be
            // a URL. Assume a local reference to the schematron and
            // attempt to resolve it.
            try {
              URL parent = label.toURI().resolve(".").toURL();
              schemaUrl = new URL(parent, uri);
            } catch (MalformedURLException mue) {
              LOG.error("getSchemaLocations:Cannot resolve schema specification '" + uri + "': " + mue.getMessage());
              throw new Exception(
                  "Cannot resolve schema specification '"
                      + uri + "': " + mue.getMessage());
            } catch (URISyntaxException e) {
              //Ignore
            }
          }
          schemaLocations.put(namespace, schemaUrl);
        }
      }
    }
    //LOG.debug("getSchemaLocations:schemaLocations.size() {}",schemaLocations.size());
    return schemaLocations;
  }

  private void spaceCheckSchematypensPattern(URL label, ProblemContainer labelProblems, List<TinyNodeImpl> xmlModels) {
    // Check for case when there is no space before schematypens and report it.
    // <?xml-model href="http://pds.nasa.gov/pds4/pds/v1/PDS4_PDS_1B00.sch"schematypens="http://purl.oclc.org/dsdl/schematron"
    // A better xml-model statement:
    // <?xml-model href="http://pds.nasa.gov/pds4/pds/v1/PDS4_PDS_1B00.sch" schematypens="http://purl.oclc.org/dsdl/schematron"

    // https://github.com/NASA-PDS/validate/issues/17 Validate schematron references and throw fatal error if invalid URI specified

    String SCHEMATYPENS_PATTERN_ONLY = "schematypens=";
    Pattern pattern = Pattern.compile(" " + Constants.SCHEMATRON_SCHEMATYPENS_PATTERN);  // Prepend a space before the pattern.
    LOG.debug("spaceCheckSchematypensPattern:label,xmlModels.size() {},{}",label,xmlModels.size());
    for (TinyNodeImpl xmlModel : xmlModels) {
      String filteredData = xmlModel.getStringValue().replaceAll("\\s+", " ");
      filteredData = filteredData.trim();
      LOG.debug("spaceCheckSchematypensPattern:label,filteredData {},{}",label,filteredData);
      if (filteredData.contains(SCHEMATYPENS_PATTERN_ONLY)) {
          int posOfSchemaType = filteredData.indexOf(SCHEMATYPENS_PATTERN_ONLY);
          if (posOfSchemaType > 0) {
              LOG.debug("spaceCheckSchematypensPattern:label,substring {},[{}],{}",label,filteredData.substring(posOfSchemaType-1,posOfSchemaType),filteredData.substring(posOfSchemaType-1,posOfSchemaType).length());
              // If the character before SCHEMATYPENS_PATTERN_ONLY is not a space, report a WARNING message.
              if (!filteredData.substring(posOfSchemaType-1,posOfSchemaType).equals(" ")) {

                  LOG.debug("spaceCheckSchematypensPattern:label,message,xmlModel.getParent().getLineNumber() {},{},{}",label,"Expecting a space before pattern [" + SCHEMATYPENS_PATTERN_ONLY + "]",xmlModel.getParent().getLineNumber());
                  LOG.error("Expecting a space before pattern:SCHEMATYPENS_PATTERN_ONLY,lineNumber,label {},{},{}",SCHEMATYPENS_PATTERN_ONLY,xmlModel.getParent().getLineNumber(),label);
                  labelProblems.addProblem(new ValidationProblem(
                      new ProblemDefinition(ExceptionType.WARNING,
                          ProblemType.BAD_SCHEMATYPENS,
                          "Invalid xml-model statement.  Expecting a space before pattern [" + SCHEMATYPENS_PATTERN_ONLY + "] in " + filteredData),
                      label,
                      -1,
                      -1));
                  break;
              }
          } 
      } else {
          LOG.error("Could not find expected pattern [{}] in label {}",SCHEMATYPENS_PATTERN_ONLY,label);
          labelProblems.addProblem(new ValidationProblem(
              new ProblemDefinition(ExceptionType.WARNING,
                  ProblemType.BAD_SCHEMATYPENS,
                  "Could not find expected pattern [" + SCHEMATYPENS_PATTERN_ONLY + "] in label " + label),
                  label,
                  -1,
                  -1));
      }
    }

    LOG.debug("spaceCheckSchematypensPattern:label,labelProblems.getProblemCount() {},{}",label,labelProblems.getProblemCount());
  }
  
  private List<URL> getSchematrons(URL label, ProblemContainer labelProblems)
      throws Exception {
    List<URL> schematronRefs = new ArrayList<URL>();
    List<TinyNodeImpl> xmlModels = new ArrayList<TinyNodeImpl>();
    try {
      XMLExtractor extractor = getExtractor(label);
      xmlModels = extractor.getNodesFromDoc(XMLExtractor.XML_MODEL_XPATH);
    } catch (Exception e) {
      throw new Exception(
          "Error occurred while attempting to find schematrons using "
              + "the XPath '" + XMLExtractor.XML_MODEL_XPATH + "': "
              + e.getMessage());
    }
    Pattern pattern = Pattern.compile(Constants.SCHEMATRON_SCHEMATYPENS_PATTERN);

    LOG.debug("getSchematrons:afor:xmlModels.size() {}",xmlModels.size());

    // Add a space check before the pattern and put a WARNING message.

    spaceCheckSchematypensPattern(label,labelProblems,xmlModels);

    LOG.debug("getSchematrons:labelProblems.getWarningCount() {}",labelProblems.getWarningCount());
    LOG.debug("getSchematrons:after:xmlModels.size() {}",xmlModels.size());

    for (TinyNodeImpl xmlModel : xmlModels) {
      String filteredData = xmlModel.getStringValue().replaceAll("\\s+", " ");
      filteredData = filteredData.trim();
      LOG.debug("validateLabel:label,filteredData {},{}",label,filteredData);
      Matcher matcher = pattern.matcher(filteredData);
      if (matcher.matches()) {
        String value = matcher.group(1).trim();
        URL schematronRef = null;
        URL parent = Utility.getParent(label);
        LOG.debug("validateLabel:label,parent {},{}",label,parent);
        try {
          schematronRef = new URL(value);
          schematronRef = new URL(Utility.makeAbsolute(parent.toString(), 
              schematronRef.toString()));
          LOG.debug("validateLabel:label,schematronRef {},{}",label,schematronRef);
        } catch (MalformedURLException ue) {
          // The schematron specification value does not appear to be
          // a URL. Assume a local reference to the schematron and
          // attempt to resolve it.
          try {
            schematronRef = new URL(parent, value);
          } catch (MalformedURLException mue) {
            labelProblems.addProblem(new ValidationProblem(
                new ProblemDefinition(ExceptionType.ERROR,
                    ProblemType.SCHEMATRON_ERROR,
                    "Cannot resolve schematron specification '"
                    + value + "': " + mue.getMessage()),
                    label,
                    xmlModel.getLineNumber(),
                    -1));
            continue;
          }
        }
        schematronRefs.add(schematronRef);
      }
    }

    LOG.debug("validateLabel:label,schematronRefs.isEmpty() {},{}",label,schematronRefs.isEmpty());

    if (schematronRefs.isEmpty()) {
      labelProblems.addProblem(new ValidationProblem(
          new ProblemDefinition(ExceptionType.WARNING,
              ProblemType.MISSING_SCHEMATRON_SPEC,
              "No schematrons specified in the label"),
          label));
    } 
    LOG.debug("validateLabel:label,labelProblems.getProblemCount() {},{}",label,labelProblems.getProblemCount());
    LOG.debug("validateLabel:label,schematronRefs {},{}",label,schematronRefs);
    //System.out.println("validateLabel:early#exit#0002");
    //System.exit(0);
    return schematronRefs;
  }
  
  private XMLExtractor getExtractor(URL label)
      throws XPathException, XPathExpressionException {
    if (extractor == null || 
        !(label.toString().equals(extractor.getSystemId())) ) {
      extractor = new XMLExtractor(label);
    }
    return extractor;
  }
}
