package org.deeplearning4j.text.treeparser;
import static org.apache.uima.fit.factory.AnalysisEngineFactory.createEngine;
import static org.apache.uima.fit.factory.AnalysisEngineFactory.createEngineDescription;

import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.cas.CAS;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.util.CasPool;
import org.cleartk.opennlp.tools.ParserAnnotator;
import org.cleartk.opennlp.tools.parser.DefaultOutputTypesHelper;
import org.cleartk.syntax.constituent.type.TopTreebankNode;
import org.cleartk.syntax.constituent.type.TreebankNode;
import org.cleartk.token.type.Sentence;
import org.cleartk.token.type.Token;
import org.cleartk.util.ParamUtil;
import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.rntn.Tree;
import org.deeplearning4j.text.annotator.PoStagger;
import org.deeplearning4j.text.annotator.SentenceAnnotator;
import org.deeplearning4j.text.annotator.StemmerAnnotator;
import org.deeplearning4j.text.annotator.TokenizerAnnotator;
import org.deeplearning4j.text.tokenizerfactory.UimaTokenizerFactory;
import org.deeplearning4j.util.MultiDimensionalMap;
import org.deeplearning4j.word2vec.sentenceiterator.SentencePreProcessor;
import org.deeplearning4j.word2vec.tokenizer.TokenizerFactory;
import org.deeplearning4j.word2vec.util.ContextLabelRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Tree parser for constituency parsing
 *
 * @author Adam Gibson
 */
public class TreeParser {

    private  AnalysisEngine parser;
    private AnalysisEngine tokenizer;
    private CasPool pool;
    private static Logger log = LoggerFactory.getLogger(TreeParser.class);
    private TokenizerFactory tf;


    public TreeParser(AnalysisEngine parser,AnalysisEngine tokenizer,CasPool pool) {
        this.parser = parser;
        this.tokenizer = tokenizer;
        this.pool = pool;
        tf = new UimaTokenizerFactory(tokenizer,true);
    }


    public TreeParser() throws Exception {
        if(parser == null) {
            parser = getParser();
        }
        if(tokenizer == null)
            tokenizer = getTokenizer();
        if(pool == null)
            pool = new CasPool(Runtime.getRuntime().availableProcessors() * 10,parser);
        tf = new UimaTokenizerFactory(tokenizer,true);

    }

    /**
     * Gets trees from text.
     * First a sentence segmenter is used to segment the training examples in to sentences.
     * Sentences are then turned in to trees and returned.
     * @param text the text to process
     * @param preProcessor the pre processor to use for pre processing sentences
     * @return the list of trees
     * @throws Exception
     */
    public List<Tree> getTrees(String text,SentencePreProcessor preProcessor)  throws Exception {
        if(text.isEmpty())
            return new ArrayList<>();

        CAS c = pool.getCas();
        if(preProcessor != null)
            text = preProcessor.preProcess(text);


        c.setDocumentText(text);
        tokenizer.process(c);
        List<Tree> ret = new ArrayList<>();
        CAS c2 = pool.getCas();
        List< Pair<String,MultiDimensionalMap<Integer,Integer,String>>> list = new ArrayList<>();
        for(Sentence sentence : JCasUtil.select(c.getJCas(),Sentence.class)) {
            List<String> tokens = new ArrayList<>();
            for(Token t : JCasUtil.selectCovered(Token.class,sentence))
                tokens.add(t.getCoveredText());

            Pair<String,MultiDimensionalMap<Integer,Integer,String>> p = ContextLabelRetriever.stringWithLabels(sentence.getCoveredText(),tf);
            c2.setDocumentText(p.getFirst());
            list.add(p);
            tokenizer.process(c2);
            parser.process(c2);

            //build the tree based on this
            TopTreebankNode node = JCasUtil.selectSingle(c.getJCas(),TopTreebankNode.class);
            ret.add(TreeFactory.buildTree(node));


        }

        pool.releaseCas(c2);


        for(Tree t : ret) {
           addPreTerminal(t);
        }


        return ret;


    }


    private void addPreTerminal(Tree t) {
        if(t.isLeaf()) {
            Tree newLeaf = new Tree(t);
            newLeaf.setLabel(t.value());
            t.children().add(newLeaf);
            newLeaf.setParent(t);
        }
        else {
            for(Tree child : t.children())
                  addPreTerminal(child);
        }
    }

    /**
     * Gets trees from text.
     * First a sentence segmenter is used to segment the training examples in to sentences.
     * Sentences are then turned in to trees and returned.
     * @param text the text to process
     * @return the list of trees
     * @throws Exception
     */
    public List<TreebankNode> getTreebankTrees(String text)  throws Exception {
        if(text.isEmpty())
            return new ArrayList<>();

        CAS c = pool.getCas();
        c.setDocumentText(text);
        tokenizer.process(c);
        List<TreebankNode> ret = new ArrayList<>();
        for(Sentence sentence : JCasUtil.select(c.getJCas(),Sentence.class)) {
            List<String> tokens = new ArrayList<>();
            CAS c2 = tokenizer.newCAS();

            for(Token t : JCasUtil.selectCovered(Token.class,sentence))
                tokens.add(t.getCoveredText());


            c2.setDocumentText(sentence.getCoveredText());
            tokenizer.process(c2);
            parser.process(c2);

            //build the tree based on this
            TopTreebankNode node = JCasUtil.selectSingle(c2.getJCas(),TopTreebankNode.class);
            log.info("Tree bank parse " + node.getTreebankParse());
            for(TreebankNode node2 : JCasUtil.select(c2.getJCas(),TreebankNode.class)) {
                log.info("Node val " + node2.getNodeValue() + " and label " + node2.getNodeType() + " and tags was " + node2.getNodeTags());
            }

            ret.add(node);


        }

        pool.releaseCas(c);

        return ret;


    }

    /**
     * Gets trees from text.
     * First a sentence segmenter is used to segment the training examples in to sentences.
     * Sentences are then turned in to trees and returned.
     *
     * This will also process sentences with the following label format:
     * <YOURLABEL> some text </YOURLABEL>
     *
     * This will allow you to train on and label sentences and label spans yourself.
     *
     * @param text the text to process
     * @param label the label for the whole sentence
     * @param labels the possible labels for the sentence
     * @return the list of trees
     * @throws Exception
     */
    public List<Tree> getTreesWithLabels(String text,String label,List<String> labels)  throws Exception {
        if(text.isEmpty())
            return new ArrayList<>();
        CAS c = pool.getCas();
        c.setDocumentText("<" + label + "> " + text + " </" + label + ">");
        tokenizer.process(c);
        List<String> lowerCaseLabels = new ArrayList<>();
        for(String s : labels)
            lowerCaseLabels.add(s.toLowerCase());
        labels = lowerCaseLabels;

        List<Tree> ret = new ArrayList<>();
        CAS c2 = pool.getCas();
        for(Sentence sentence : JCasUtil.select(c.getJCas(),Sentence.class)) {
           if(sentence.getCoveredText().isEmpty())
               continue;

            List<String> tokens = new ArrayList<>();
            for(Token t : JCasUtil.selectCovered(Token.class,sentence))
                tokens.add(t.getCoveredText());

            try {
                Pair<String, MultiDimensionalMap<Integer, Integer, String>> stringsWithLabels = ContextLabelRetriever.stringWithLabels(sentence.getCoveredText(), tf);
                c2.setDocumentText(stringsWithLabels.getFirst());
                tokenizer.process(c2);
                parser.process(c2);

                //build the tree based on this
                //damn it
                List<TopTreebankNode> nodes = new ArrayList<>(JCasUtil.select(c2.getJCas(),TopTreebankNode.class));
                if(nodes.size() > 1) {
                    log.warn("More than one top level node for a treebank parse. Only accepting first input node.");
                }

                else if(nodes.isEmpty()) {
                    c2.reset();
                    continue;
                }

                TopTreebankNode node = nodes.get(0);
                ret.add(TreeFactory.buildTree(node,stringsWithLabels,labels));
                c2.reset();

            }catch(Exception e) {
                log.warn("Unable to parse " + sentence.getCoveredText());
                c2.reset();
                continue;
            }



        }

        pool.releaseCas(c);
        pool.releaseCas(c2);

        return ret;


    }


    /**
     * Gets trees from text.
     * First a sentence segmenter is used to segment the training examples in to sentences.
     * Sentences are then turned in to trees and returned.
     *
     * This will also process sentences with the following label format:
     * <YOURLABEL> some text </YOURLABEL>
     *
     * This will allow you to train on and label sentences and label spans yourself.
     *
     * @param text the text to process
     * @param labels
     * @return the list of trees
     * @throws Exception
     */
    public List<Tree> getTreesWithLabels(String text,List<String> labels)  throws Exception {
        CAS c = pool.getCas();
        c.setDocumentText(text);
        tokenizer.process(c);
        List<String> lowerCaseLabels = new ArrayList<>();
        for(String s : labels)
            lowerCaseLabels.add(s.toLowerCase());
        labels = lowerCaseLabels;

        List<Tree> ret = new ArrayList<>();
        CAS c2 = pool.getCas();
        for(Sentence sentence : JCasUtil.select(c.getJCas(),Sentence.class)) {
            List<String> tokens = new ArrayList<>();
            for(Token t : JCasUtil.selectCovered(Token.class,sentence))
                tokens.add(t.getCoveredText());

            Pair<String,MultiDimensionalMap<Integer,Integer,String>> stringsWithLabels = ContextLabelRetriever.stringWithLabels(sentence.getCoveredText(),tf);
            c2.setDocumentText(stringsWithLabels.getFirst());



            tokenizer.process(c2);
            parser.process(c2);

            //build the tree based on this
            //damn it
            List<TopTreebankNode> nodes = new ArrayList<>(JCasUtil.select(c2.getJCas(),TopTreebankNode.class));
            if(nodes.size() > 1) {
                log.warn("More than one top level node for a treebank parse. Only accepting first input node.");
            }

            else if(nodes.isEmpty()) {
                c2.reset();
                continue;
            }

            TopTreebankNode node = nodes.get(0);
            ret.add(TreeFactory.buildTree(node,stringsWithLabels,labels));
            c2.reset();

        }

        pool.releaseCas(c);
        pool.releaseCas(c2);

        return ret;


    }

    /**
     * Gets trees from text.
     * First a sentence segmenter is used to segment the training examples in to sentences.
     * Sentences are then turned in to trees and returned.
     * @param text the text to process
     * @return the list of trees
     * @throws Exception
     */
    public List<Tree> getTrees(String text)  throws Exception {
        CAS c = pool.getCas();
        c.setDocumentText(text);
        tokenizer.process(c);
        List<Tree> ret = new ArrayList<>();
        CAS c2 = pool.getCas();
        for(Sentence sentence : JCasUtil.select(c.getJCas(),Sentence.class)) {
            List<String> tokens = new ArrayList<>();
            for(Token t : JCasUtil.selectCovered(Token.class,sentence))
                tokens.add(t.getCoveredText());


            c2.setDocumentText(sentence.getCoveredText());
            tokenizer.process(c2);
            parser.process(c2);

            //build the tree based on this
            TopTreebankNode node = JCasUtil.selectSingle(c2.getJCas(),TopTreebankNode.class);
            log.info("Tree bank parse " + node.getTreebankParse());
            for(TreebankNode node2 : JCasUtil.select(c2.getJCas(),TreebankNode.class)) {
                log.info("Node val " + node2.getNodeValue() + " and label " + node2.getNodeType() + " and tags was " + node2.getNodeTags());
            }

            ret.add(TreeFactory.buildTree(node));
            c2.reset();

        }

        pool.releaseCas(c);
        pool.releaseCas(c2);

        return ret;


    }


    public static AnalysisEngine getTokenizer() throws Exception {
        return createEngine(
                createEngineDescription(
                        SentenceAnnotator.getDescription(),
                        TokenizerAnnotator.getDescription(),
                        PoStagger.getDescription("en"),
                        StemmerAnnotator.getDescription("English")

                )
        );
    }

    public static AnalysisEngine getParser() throws Exception {
        return createEngine(
                createEngineDescription(
                        createEngineDescription(
                                ParserAnnotator.class,
                                ParserAnnotator.PARAM_USE_TAGS_FROM_CAS,
                                true,
                                ParserAnnotator.PARAM_PARSER_MODEL_PATH,
                                ParamUtil.getParameterValue(ParserAnnotator.PARAM_PARSER_MODEL_PATH, "/models/en-parser-chunking.bin"),
                                ParserAnnotator.PARAM_OUTPUT_TYPES_HELPER_CLASS_NAME,
                                DefaultOutputTypesHelper.class.getName())));


    }

}
