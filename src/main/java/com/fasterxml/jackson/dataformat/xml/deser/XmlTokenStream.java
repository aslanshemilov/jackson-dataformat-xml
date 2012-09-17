package com.fasterxml.jackson.dataformat.xml.deser;

import java.io.IOException;
import javax.xml.stream.*;

import org.codehaus.stax2.XMLStreamLocation2;
import org.codehaus.stax2.XMLStreamReader2;
import org.codehaus.stax2.ri.Stax2ReaderAdapter;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.dataformat.xml.util.StaxUtil;

/**
 * Simple helper class used on top of STAX {@link XMLStreamReader} to further
 * abstract out all irrelevant details, and to expose equivalent of flat token
 * stream with no "fluff" tokens (comments, processing instructions, mixed
 * content) all of which is just to simplify
 * actual higher-level conversion to JSON tokens
 */
public class XmlTokenStream
{
    // // // main token states:
    
    public final static int XML_START_ELEMENT = 1;
    public final static int XML_END_ELEMENT = 2;
    public final static int XML_ATTRIBUTE_NAME = 3;
    public final static int XML_ATTRIBUTE_VALUE = 4;
    public final static int XML_TEXT = 5;
    public final static int XML_END = 6;

    // // // token replay states

    private final static int REPLAY_START_DUP = 1;
    private final static int REPLAY_END = 2;
    private final static int REPLAY_START_DELAYED = 3;
    
    /*
    /**********************************************************************
    /* Configuration
    /**********************************************************************
     */

    final protected XMLStreamReader2 _xmlReader;

    final protected Object _sourceReference;
    
    /*
    /**********************************************************************
    /* Parsing state
    /**********************************************************************
     */

    protected int _currentState;

    protected int _attributeCount;
    
    /**
     * Index of the next attribute of the current START_ELEMENT
     * to return (as field name and value pair), if any; -1
     * when no attributes to return
     */
    protected int _nextAttributeIndex = 0;

    protected String _localName;

    protected String _namespaceURI;

    protected String _textValue;
    
    /*
    /**********************************************************************
    /* State for handling virtual wrapping
    /**********************************************************************
     */
    
    /**
     * Flag used to indicate that given element should be "replayed".
     */
    protected int _repeatElement;

    /**
     * Wrapping state, if any active (null if none)
     */
    protected ElementWrapper _currentWrapper;

    /**
     * In cases where we need to 'inject' a virtual END_ELEMENT, we may also
     * need to restore START_ELEMENT afterwards; if so, this is where names
     * are held.
     */
    protected String _nextLocalName;
    protected String _nextNamespaceURI;
    
    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    public XmlTokenStream(XMLStreamReader xmlReader, Object sourceRef)
    {
        _sourceReference = sourceRef;
        // Let's ensure we point to START_ELEMENT...
        if (xmlReader.getEventType() != XMLStreamConstants.START_ELEMENT) {
            throw new IllegalArgumentException("Invalid XMLStreamReader passed: should be pointing to START_ELEMENT ("
                    +XMLStreamConstants.START_ELEMENT+"), instead got "+xmlReader.getEventType());
        }
        _xmlReader = Stax2ReaderAdapter.wrapIfNecessary(xmlReader);
        _currentState = XML_START_ELEMENT;
        _localName = _xmlReader.getLocalName();
        _namespaceURI = _xmlReader.getNamespaceURI();
        _attributeCount = _xmlReader.getAttributeCount();
    }

    public XMLStreamReader2 getXmlReader() {
        return _xmlReader;
    }

    /*
    /**********************************************************************
    /* Public API
    /**********************************************************************
     */

    // DEBUGGING
    /*
    public int next() throws IOException 
    {
        int n = next0();
        switch (n) {
        case XML_START_ELEMENT: 
            System.out.println(" XML-token: XML_START_ELEMENT '"+_localName+"'");
            break;
        case XML_END_ELEMENT: 
            System.out.println(" XML-token: XML_END_ELEMENT '"+_localName+"'");
            break;
        case XML_ATTRIBUTE_NAME: 
            System.out.println(" XML-token: XML_ATTRIBUTE_NAME '"+_localName+"'");
            break;
        case XML_ATTRIBUTE_VALUE: 
            System.out.println(" XML-token: XML_ATTRIBUTE_VALUE '"+_textValue+"'");
            break;
        case XML_TEXT: 
            System.out.println(" XML-token: XML_TEXT '"+_textValue+"'");
            break;
        case XML_END: 
            System.out.println(" XML-token: XML_END");
            break;
        default:
            throw new IllegalStateException();
        }
        return n;
    }
    */
    
    public int next() throws IOException 
    {
        if (_repeatElement != 0) {
            return _handleRepeatElement();
        }
        try {
            return _next();
        } catch (XMLStreamException e) {
            StaxUtil.throwXmlAsIOException(e);
            return -1;
        }
    }
    
    public void skipEndElement() throws IOException
    {
        int type = next();
        if (type != XML_END_ELEMENT) {
            throw new IOException("Expected END_ELEMENT, got event of type "+type);
        }
    }

    public int getCurrentToken() { return _currentState; }

    public String getText() { return _textValue; }
    public String getLocalName() { return _localName; }
    public String getNamespaceURI() { return _namespaceURI; }
    public boolean hasAttributes() {
        return (_currentState == XML_START_ELEMENT) && (_attributeCount > 0);
    }
    
    public void closeCompletely() throws IOException
    {
        try {
            _xmlReader.closeCompletely();
        } catch (XMLStreamException e) {
            StaxUtil.throwXmlAsIOException(e);
        }
    }

    public void close() throws IOException
    {
        try {
            _xmlReader.close();
        } catch (XMLStreamException e) {
            StaxUtil.throwXmlAsIOException(e);
        }
    }

    public JsonLocation getCurrentLocation() {
        return _extractLocation(_xmlReader.getLocationInfo().getCurrentLocation());
    }
    public JsonLocation getTokenLocation() {
        return _extractLocation(_xmlReader.getLocationInfo().getStartLocation());
    }

    /*
    /**********************************************************************
    /* Internal API: more esoteric methods
    /**********************************************************************
     */
    
    /**
     * Method used to add virtual wrapping, which just duplicates START_ELEMENT
     * stream points to, and its matching closing element.
     * 
     * @since 2.1
     */
    protected void repeatStartElement()
    {
        // sanity check: can only be used when just returned START_ELEMENT:
        if (_currentState != XML_START_ELEMENT) {
            throw new IllegalStateException("Current state not XML_START_ELEMENT ("
                    +XML_START_ELEMENT+") but "+_currentState);
        }
        // Important: add wrapper, to keep track...
        if (_currentWrapper == null) {
            _currentWrapper = ElementWrapper.matchingWrapper(_currentWrapper, _localName, _namespaceURI);
        } else {
            _currentWrapper = ElementWrapper.matchingWrapper(_currentWrapper.getParent(), _localName, _namespaceURI);
        }
        _repeatElement = REPLAY_START_DUP;
    }

    /**
     * Method called to skip any attributes current START_ELEMENT may have,
     * so that they are not returned as token.
     * 
     * @since 2.1
     */
    protected void skipAttributes()
    {
        if (_currentState == XML_START_ELEMENT) {
            _attributeCount = 0;
        } else if (_currentState == XML_ATTRIBUTE_NAME) {
            _attributeCount = 0;
            _currentState = XML_START_ELEMENT;
        } else {
            throw new IllegalStateException("Current state not XML_START_ELEMENT or XML_ATTRIBUTE_NAME ("
                    +XML_START_ELEMENT+") but "+_currentState);
        }
    }
    
    /*
    /**********************************************************************
    /* Internal methods, parsing
    /**********************************************************************
     */

    private final int _next() throws XMLStreamException
    {
        switch (_currentState) {
        case XML_ATTRIBUTE_VALUE:
            ++_nextAttributeIndex;
            // fall through
        case XML_START_ELEMENT: // attributes to return?
            if (_nextAttributeIndex < _attributeCount) {
                _localName = _xmlReader.getAttributeLocalName(_nextAttributeIndex);
                _namespaceURI = _xmlReader.getAttributeNamespace(_nextAttributeIndex);
                _textValue = _xmlReader.getAttributeValue(_nextAttributeIndex);
                return (_currentState = XML_ATTRIBUTE_NAME);
            }
            // otherwise need to find START/END_ELEMENT or text
            String text = _collectUntilTag();
            // If it's START_ELEMENT, ignore any text
            if (_xmlReader.getEventType() == XMLStreamReader.START_ELEMENT) {
                return _initStartElement();
            }
            // For END_ELEMENT we will return text, if any
            if (text != null) {
                _textValue = text;
                return (_currentState = XML_TEXT);
            }
            return _handleEndElement();
        case XML_ATTRIBUTE_NAME:
            // if we just returned name, will need to just send value next
            return (_currentState = XML_ATTRIBUTE_VALUE);
        case XML_TEXT:
            // text is always followed by END_ELEMENT
            return _handleEndElement();
        case XML_END:
            return XML_END;
//            throw new IllegalStateException("No more XML tokens available (end of input)");
        }

        // Ok: must be END_ELEMENT; see what tag we get (or end)
        switch (_skipUntilTag()) {
        case XMLStreamConstants.END_DOCUMENT:
            return (_currentState = XML_END);
        case XMLStreamConstants.END_ELEMENT:
            return _handleEndElement();
        }
        // START_ELEMENT...
        return _initStartElement();
    }
    
    private final String _collectUntilTag() throws XMLStreamException
    {
        String text = null;
        while (true) {
            switch (_xmlReader.next()) {
            case XMLStreamConstants.START_ELEMENT:
            case XMLStreamConstants.END_ELEMENT:
            case XMLStreamConstants.END_DOCUMENT:
                return text;
                // note: SPACE is ignorable (and seldom seen), not to be included
            case XMLStreamConstants.CHARACTERS:
            case XMLStreamConstants.CDATA:
                if (text == null) {
                    text = _xmlReader.getText();
                } else { // can be optimized in future, if need be:
                    text += _xmlReader.getText();
                }
                break;
            default:
                // any other type (proc instr, comment etc) is just ignored
            }
        }
    }

    private final int _skipUntilTag() throws XMLStreamException
    {
        while (_xmlReader.hasNext()) {
            int type;
            switch (type = _xmlReader.next()) {
            case XMLStreamConstants.START_ELEMENT:
            case XMLStreamConstants.END_ELEMENT:
            case XMLStreamConstants.END_DOCUMENT:
                return type;
            default:
                // any other type (proc instr, comment etc) is just ignored
            }
        }
        throw new IllegalStateException("Expected to find a tag, instead reached end of input");
    }
    
    /*
    /**********************************************************************
    /* Internal methods, other
    /**********************************************************************
     */
    
    private final int _initStartElement() throws XMLStreamException
    {
        final String ns = _xmlReader.getNamespaceURI();
        final String localName = _xmlReader.getLocalName();
        _nextAttributeIndex = 0;
        /* Support for virtual wrapping: in wrapping, may either
         * create a new wrapper scope (if in sub-tree, or matches
         * wrapper element itself), or implicitly close existing
         * scope.
         */
        if (_currentWrapper != null) {
            if (_currentWrapper.matchesWrapper(localName, ns)) {
                _currentWrapper = _currentWrapper.intermediateWrapper();
            } else {
                // implicit end is more interesting:
                _localName = _currentWrapper.getWrapperLocalName();
                _namespaceURI = _currentWrapper.getWrapperNamespace();
                _currentWrapper = _currentWrapper.getParent();
//System.out.println(" START_ELEMENT ("+localName+") not matching '"+_localName+"'; add extra XML-END-ELEMENT!");
                // Important! We also need to restore the START_ELEMENT, so:
                _nextLocalName = localName;
                _nextNamespaceURI = ns;
                _repeatElement = REPLAY_START_DELAYED;
                return (_currentState = XML_END_ELEMENT);
            }
        }
        _attributeCount = _xmlReader.getAttributeCount();
        _localName = localName;
        _namespaceURI = ns;
        return (_currentState = XML_START_ELEMENT);
    }

    /**
     * Method called to handle details of repeating "virtual"
     * start/end elements, needed for handling 'unwrapped' lists.
     */
    protected int _handleRepeatElement() throws IOException 
    {
        int type = _repeatElement;
        _repeatElement = 0;
        if (type == REPLAY_START_DUP) {
//System.out.println("handleRepeat for START_ELEMENT: "+_localName+" ("+_xmlReader.getLocalName()+")");
            // important: add the virtual element second time, but not with name to match
            _currentWrapper = _currentWrapper.intermediateWrapper();
            return XML_START_ELEMENT;
        }
        if (type == REPLAY_END) {
//System.out.println("handleRepeat for END_ELEMENT: "+_localName+" ("+_xmlReader.getLocalName()+")");
            _localName = _xmlReader.getLocalName();
            _namespaceURI = _xmlReader.getNamespaceURI();
            return XML_END_ELEMENT;
        }
        if (type == REPLAY_START_DELAYED) {
            _currentWrapper = _currentWrapper.intermediateWrapper();
            _localName = _nextLocalName;
            _namespaceURI = _nextNamespaceURI;
            _nextLocalName = null;
            _nextNamespaceURI = null;
            
//System.out.println("handleRepeat for START_DELAYED: "+_localName+" ("+_xmlReader.getLocalName()+")");

            return XML_START_ELEMENT;
        }
        throw new IllegalStateException("Unrecognized type to repeat: "+type);
    }
    
    private final int _handleEndElement()
    {
        if (_currentWrapper != null) {
            ElementWrapper w = _currentWrapper;
            // important: if we close the scope, must duplicate END_ELEMENT as well
            if (w.isMatching()) {
                _repeatElement = REPLAY_END;
                _localName = w.getWrapperLocalName();
                _namespaceURI = w.getWrapperNamespace();
//System.out.println(" requestRepeat of END_ELEMENT '"+_localName);
            } else {
                _currentWrapper = _currentWrapper.getParent();
            }
        }
        return (_currentState = XML_END_ELEMENT);
    }
    
    private JsonLocation _extractLocation(XMLStreamLocation2 location)
    {
        if (location == null) { // just for impls that might pass null...
            return new JsonLocation(_sourceReference, -1, -1, -1);
        }
        return new JsonLocation(_sourceReference,
                location.getCharacterOffset(),
                location.getLineNumber(),
                location.getColumnNumber());
    }
    
}