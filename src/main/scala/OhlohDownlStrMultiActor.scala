import java.io.File
import java.net.{HttpURLConnection, InetAddress, URL}
import java.nio.file.{Files, Paths}

import akka.actor._
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.elasticsearch.client.Client
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress

import scala.xml.XML


case class StartStream(ohlohURL : String)
case class StartDownloading(projectId: String, projectURL : String,projectName:String,projectDesc : String)
case class StartParsing(allFiles : Array[File],projectName:String,projectDesc : String, projectURL : String)
case class StartFileIndex(projectName: String, projectDesc : String, fileName : String, fileExt : String, content : String, mappingType: String, projectURL : String, maincount : Int)
case object StartIndexing
case class createTagIndex(projectId: String, projectName: String, projectURL: String, projectDesc : String, projectTags : String)
case object getSearchResponses
case object testMessage
case object stopActor

case object StreamAllGithubProjects

/** This file is for interacting with the projects streamed using OHLOH API.
  * The API is called to fetch projects that have Github in there description
  * The repositories for these searched projects are downloaded locally
  * and then each of the files from each project (including only code related files and removing
  * all types of different config files)
  * */

/* this actor is responsible for streaming projects from OHLOH API - for our search engine, we
* are streaming only github projects.*/
class StreamDownlProjects(ESClient: ActorRef) extends Actor {
  var maincount = 0

  //var projectName = Seq[String]()
  var projectDesc = ""
  var homePageURL = ""
  var mainlanguageTag = ""

  /* this method is use to open the HTTP connection and retrieve the XML from OHLOH API*/
  // @throws(classOf[java.io.IOException])
  // @throws(classOf[java.net.SocketTimeoutException])
  def get(url: String,
          connectTimeout: Int = 100000,
          readTimeout: Int = 100000,
          requestMethod: String = "GET"):String =
  {
    val ohlohConnection = (new URL(url)).openConnection.asInstanceOf[HttpURLConnection]
    var content = ""
    try{
      ohlohConnection.setConnectTimeout(connectTimeout)
      ohlohConnection.setReadTimeout(readTimeout)
      ohlohConnection.setRequestMethod(requestMethod)
      /* get the input stream from the HTTP connection to read the response */
      val inputStream = ohlohConnection.getInputStream

      /* fetch the contents from the Opened HTTP connection = this basically fetches the xml response
      * that we receive from OHLOH API */
      content = io.Source.fromInputStream(inputStream).mkString
      if (inputStream != null) inputStream.close
      content
    }
    catch {
      case ex: java.io.IOException => {
        println("IO Exception :"+ex.getMessage)
        ""
      }
      case ex:java.net.SocketTimeoutException =>{
        println("Socket Time out"+ex.getMessage)
        ""
      }
      case ex:java.io.FileNotFoundException => {
        println("URL does not exists: "+ex.getMessage)
        ""
      }
      case ex:Exception =>{
        println("General exception in opening "+ex.getMessage)
        ""
      }
    }
    content

  }


  /* Stream the projects from OHLOH API*/
  def streamProject(allProjURL:String) = {
    val content = get(allProjURL)
    val xml = XML.loadString(content)
    xml
  }


  def receive = {
    /* Start stream is the first method that interacts and streams the projects.*/
    case StartStream(ohlohURL) =>
      println("in Start Stream")

      /* get the xml for 10 projects which have github in their name or description*/
      val streams =  streamProject(ohlohURL)

      /* parse the xml response to get  all the project ids -10 as received from the REST API call to OHLOH*/
      val projectIds = (streams \\ "response" \\ "project" \\ "id").theSeq


      /** this listIds contains the project ids as fetched from ohloh.
        * there is a default id already provided.
        */
      var listIds = List[Int]()

      /* fetch all the project ids into a list */
      for(ids <- projectIds) {
        listIds ::= ids.text.toInt
      }

      var streamURL =""

      /* iterate through all the project ids individually to get their xml response */
      for(eachListId <- listIds)
      {
        println("Project Id: " + eachListId)

        streamURL = "https://www.openhub.net/projects/" + eachListId + ".xml?api_key=c3943bda503b24b9ed76ba00add525ecd330720aa437f82fa3bc4cbeab330b7b"
        println("Project Stream URL: " + streamURL)

        /* get the xml response for each of the project ids  */
        val content = get(streamURL)
        if (!content.isEmpty)
        {
          val singleStream = XML.loadString(content)

          /* get the information from the xml - home page url, project name
        * description, main language tags and tags associated with this project*/

          homePageURL = (singleStream \\ "response" \\ "project" \\ "homepage_url").text

          println("project home page url : " + homePageURL)

          var projectName = (singleStream \\ "response" \\ "project" \\ "name").theSeq
          projectName = projectName.take(1)
          println("project name: " + projectName.text.toString)

          projectDesc = (singleStream \\ "response" \\ "project" \\ "description").text
          println("project description: " + projectDesc.toString)

          mainlanguageTag = (singleStream \\ "response" \\ "project" \\ "analysis" \\ "main_language_name").text

          val allTags = (singleStream \\ "response" \\ "project" \\ "tags").theSeq

          val listTags = StringBuilder.newBuilder

          for (eachTag <- allTags) {
            listTags.append(eachTag.text.toString.replaceAll(" ", "").replaceAll("\\n", " "))
          }

          /** ************ creating the index for tags associated with a project *****************************/
          ESClient ! createTagIndex(eachListId.toString, projectName.text.toString, homePageURL.toString, projectDesc, listTags.toString())

          /** ************ creating the index for different language file associated with a project *****************************/

          ESClient ! StartDownloading(eachListId.toString, homePageURL.toString(), projectName.text.toString, projectDesc)
        }
      }

    /* This method is for parsing each of the files as present in the project */
    case StartParsing(allFiles,projectName,projectDesc,projectURL) => {

      println("in main actor : start parsing")

      var plainText = ""
      maincount += allFiles.length

      for (filename <- allFiles) {
        /*  extract the contents from each of the file as simple bag of words, removing all specail
         * characters except underscore. */
        plainText = new String(Files.readAllBytes(Paths.get(filename.getAbsolutePath)))


        var tempFileExt = filename.toString()
        tempFileExt = tempFileExt.substring(tempFileExt.lastIndexOf(".") + 1, tempFileExt.length)

        var tempFileName = filename.toString
        tempFileName = tempFileName.substring(tempFileName.lastIndexOf("\\") + 1, tempFileName.length)


        plainText = plainText.replaceAll("[^\\w]", " ")

        /* once the content is extracted as plain bag of words - start indexing each file for each project with index as languages*/
        sender ! StartFileIndex(projectName, projectDesc, tempFileName, tempFileExt, plainText.toString, "languages", projectURL, maincount)

        plainText = ""
      }
    }

    /* fetch the responses after running the index */
    //ESClient ! getSearchResponses

  }
}

/* this actor is responsible for opening the client for elastic search on google cloud*/

class ESClient extends Actor {

  var count : Int = 0

  private val port = 9300

  private val nodes = List("104.197.155.244")

  private val addresses = nodes.map { host => new InetSocketTransportAddress(InetAddress.getByName(host), port) }

  lazy private val settings = Settings.settingsBuilder().put("cluster.name", "elasticsearch").build()

  println(s"ElasticClient: Nodes => $nodes , Port => {$port}");

  /* inet address for connecting to google cloud*/
  val inetAddress : InetAddress  = InetAddress.getByName("104.197.155.244")

  //InetAddress.getByName("104.197.155.244")

  /* client for connecting to the google elastic search */
  val client: Client = TransportClient.builder().settings(settings).build().addTransportAddresses(new InetSocketTransportAddress(inetAddress, 9300))

  /* this function is use to iterate through all folder and fetch files with the extensions */
  def recursiveListFiles(f: File): Array[File] = {

    val currentFiles = f.listFiles

    /* the documents indexed are only the code files with the below extensions*/
    val acceptedExtensions = currentFiles.filter(_.getName.matches(".*\\.(go|html|java|js|css|rb|slim|php|c|h|cpp|hpp|cs|xml|yaml||yml|pod|xib|sh|dart|json|py)$"))
    acceptedExtensions ++ currentFiles.filter(_.isDirectory).flatMap(recursiveListFiles(_))
  }


  def receive = {

    /* Download the project on the local drive */
    case StartDownloading(projectId,projectURL,projectName,projectDesc) => {

      var modifiedProjectURL = projectURL

      /* since all the URLS for github are moved to HTTPS, so if any URL has HTTP => automatically replace it with
       * HTTPS */
      if (projectURL.startsWith("http://github")) {
        modifiedProjectURL = projectURL.replace("http:", "https:")
      }
      println("Download from the url: " + modifiedProjectURL)

      /* downlaod the projects only with github repositories*/
      if (modifiedProjectURL.startsWith("https://github")) {
        /* local folder to create the repository downloaded from the github*/
        var localFolder = "TestGitRepository_" + projectId

        FileUtils.deleteDirectory(new File(localFolder))

        /* using JGit - download the github repository*/
        val localPath = new File(localFolder)
        Git.cloneRepository().setURI(modifiedProjectURL).setDirectory(localPath).call()

        val files = recursiveListFiles(localPath)

        /* once all the files are downloaded from github - start parsing the content  of each of the file
        * into bag of words in order to process the index */
        sender ! StartParsing(files, projectName, projectDesc, projectURL)

      }
    }

    /* this method is use to index each file as downloaded from github for each project. */
    case  StartFileIndex(projectName,projectDesc,fileName,fileExt,content,mappingType,projectURL,maincount) => {

      count+=1
      /* the index will be created for the files as extracted from the local folder*/
      println("Creating the index for:  " + fileName + " . Please wait for all the files to be processed")
      val tempprojectName = projectName.replaceAll("[^\\w]", " ")
      val tempprojectDesc = projectDesc.replaceAll("[^\\w]", " ")

      /* the json string for this index contains the projectname, description, url and the file content
      * this file content is simply bag of words and all special characters are removed from this. */
      val jsonString =
      s"""
      {
        "filename":"$fileName",
        "projectname": "$tempprojectName",
        "projecturl": "$projectURL",
        "description":"$tempprojectDesc",
        "content" : "$content"
      }"""

      //println("json for file: "+fileName+" content: "+jsonString)
      /* the index created is basically indexname : language, type: language type which is nothing
       but the extension of the file , for example: go, and the mapping fields contains the json data*/
      client.prepareIndex(mappingType,fileExt.toLowerCase()).setSource(jsonString).get()


    }

    /**************************************************************************************************/

    /* create the index for tags - this is another tag for searching the projects */
    case createTagIndex(projectId, projectName, projectURL, projectDesc, projectTags) => {
      val splitTag = projectTags.split(" ")

      /* fetch all the tags and then if it is not blank then create the index*/
      for (eachsplitTag <- splitTag) {
        if (!eachsplitTag.isEmpty) {

          val tempprojectName = projectName.replaceAll("[^\\w]", " ")
          val tempprojectDesc = projectDesc.replaceAll("[^\\w]", " ")
          var tempprojectTags = projectTags.replaceAll("[^\\w]", " ")
          tempprojectTags = tempprojectTags.trim
          println("each trimmed tags: " + tempprojectTags)
          var tempeachsplitTag = eachsplitTag.replaceAll(" ", "")
          tempeachsplitTag = tempeachsplitTag.toLowerCase()

          val jsonString =
            s"""
          {
            "projectId":"$projectId",
            "projectname": "$tempprojectName",
            "projecturl": "$projectURL",
            "description": "$tempprojectDesc",
            "content" : "$tempprojectTags"
          }"""

          //println("in ESClient : create tag index :"+ tempeachsplitTag +" for each project:  "+projectId+ " json for tags: "+jsonString)

          /* create another index with index name as tags , type with the tag name as received in the xml , example:
          * maven or eclipse and the json string contains only the basic information of the project. */
          client.prepareIndex("tags", tempeachsplitTag).setSource(jsonString).get()
        }

      }
    }

    /****************************************************************************************************************/

    /* check if the client is working properly and fetching all responses. */
    case getSearchResponses : String => {
      val resp = client.prepareSearch("languages").setTypes("php", "go", "dart", "slim").get()
      println(resp)
    }

  }
}

/* this object starts the processing and calling of the actors and their methods.*/

object StreamDownlESProject extends App {

  val system = ActorSystem("SearchSystem")

  /* reference for calling Elastic search client actor*/
  val callESClient = system.actorOf(Props[ESClient], name = "ESClient")

  /* reference for calling Ohloh project streaming and downloading actor */
  val streamDownlProj = system.actorOf(Props(new StreamDownlProjects(callESClient)), name = "StreamDownlProjects")

  /* call the StartStream - to start streaming the projects from OHLOH API and then process them to create
  * index in elastic search */
  streamDownlProj ! StartStream("https://www.openhub.net/projects.xml?query=github&api_key=c3943bda503b24b9ed76ba00add525ecd330720aa437f82fa3bc4cbeab330b7b")

  Thread.sleep(300000)
  println("No for more files to download. It will exit after 5 seconds")

  Thread.sleep(5)
  println("System exiting")
  system.stop(callESClient)
  system.stop(streamDownlProj)
  System.exit(0)
}