import java.io.File
import java.net.InetAddress

import akka.actor.{ActorSystem, Props}
import org.elasticsearch.action.search.SearchType
import org.elasticsearch.client.Client
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.index.query.QueryBuilders
import org.scalatest.FunSuite
import play.api.libs.json.Json

/**
  * Created by kruti on 11/9/2016.
  * the below test cases are the unit test cases for testing the streaming of project
  * from OHLOH API and check whether the repositories are downloaded on the local system
  * and if indexes are successfully created on ElasticSearch Engine
  */
class StreamingDownlUnitTest extends FunSuite {

  val system = ActorSystem("SearchSystem")

  /* test client actor for calling Elastic Search Client for making the connection and checking indexes.*/
  val testCallESClient = system.actorOf(Props[ESClient], name = "ESClient")

  /* reference for calling Ohloh project streaming and downloading actor */
  val testStreamDownlProj = system.actorOf(Props(new StreamDownlProjects(testCallESClient)), name = "StreamDownlProjects")

  /* This test case is to stream a single project */
  test("Test Streaming and Downloading")
  {
    println("Test streaming, downloading and indexing of a project")
    val resp1 = testStreamDownlProj ! StartStream("https://www.openhub.net/projects/721873.xml?api_key=c3943bda503b24b9ed76ba00add525ecd330720aa437f82fa3bc4cbeab330b7b")

    println("resp1: "+resp1)

    println("Waiting for indexes to be created")
    Thread.sleep(10000)

    val settings = Settings.settingsBuilder().put("cluster.name", "elasticsearch").build()

    /* inet address for connecting to google cloud*/
    val inetAddress : InetAddress  = InetAddress.getByName("104.197.155.244")

    /* client for connecting to the google elastic search */
    val client: Client = TransportClient.builder().settings(settings).build().addTransportAddresses(new InetSocketTransportAddress(inetAddress, 9300))

    /* check if the indexes are created on Elastic Search for project name as downloaded.*/
    val resp = client.prepareSearch("languages").setSearchType(SearchType.DFS_QUERY_THEN_FETCH).
      setQuery(QueryBuilders.wildcardQuery("projectname", "*" + "owl" + "*"))
      .setSize(1).get()
    println(resp)
    val json_response=Json.parse(resp.toString)

    var indexedProjectName = ((json_response \ "hits" \ "hits")(0) \ "_source" \ "projectname" ).toString()

    indexedProjectName = indexedProjectName.replaceAll("\"","")
    println("Indexed Projectname: "+indexedProjectName)

    /* assert if the project as streamed using OHLOP API is indexed onto the Elastic Search Engine
    * If the project is indexed - this asserts that all the files for that project are also indexed
    * on the Elastic Search Engine. */
    assert(indexedProjectName.equals("Owl Status Page"))
  }

  /* this test case is to check if the repository of code as fetched from Github are downloaded on the local system*/
  test("Test Downloading projects")
  {
    println("Test downloading of Github projects on local system")
    testCallESClient ! StartDownloading("668296","https://github.com/Mic92/github-tags","github-tags","sinatra app to generate rss feeds with the latest git tags of a project on github")

    println("downloading the project repository for project: 668296")
    Thread.sleep(10000)

    /* check if the repository as fetched from the Github for  a particular project is downloaded on local system */
    var checkDir : File  = new File("TestGitRepository_668296")
    assert(checkDir.exists()===true)
    println("Project repository downloaded in local folder as : TestGitRepository_668296")

  }

}