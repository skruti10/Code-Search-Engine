import java.net.InetAddress

import play.api.libs.json._
import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.elasticsearch.action.search.{SearchResponse, SearchType}
import org.elasticsearch.client.Client
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.index.query.QueryBuilders

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.StdIn

/**
  * Created by singsand on 11/6/20
  * 16.
  */


//list of cases in actors
case class FetchList1(lang: String,count: String,projectname: String,keyword: String)
case class Parse1(response: SearchResponse)
case class Parse2(response: SearchResponse)

case class Discard(keyword: String)
case class FetchList2(tags: String,count: String)


//Object which creates instance of class contain the main code
object SearchEngine {
  def main(args: Array[String]): Unit = {
    val inst: SearchEngine1 = new SearchEngine1()
    inst.method(new Array[String](5))
  }
}

//This class creates web service and calls actors to process responses from web service
class SearchEngine1(){
  def method(args: Array[String]): Unit = {

    //Inititate an actor system
    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()


    //Create references to actors to be used later
    val discardstopwords = system.actorOf(Props[discardStopwords], name = "DiscardStopwords")
    val parseresponse = system.actorOf(Props[ParseResponse], name = "ParseResponse")
    val fetch = system.actorOf(Props(new Fetch(parseresponse)), name = "FetchList")

    // needed for the future map/flatmap in the end
    implicit val executionContext = system.dispatcher

    //Create a handler for when web service responds with just URL, that is the homepage
    object Route1 {
      val route =
        path("") {
          akka.http.scaladsl.server.Directives.get {
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Welcome to the Search Engine</h1>" +
              "<b>Please use below extensions to the above URL ( <a href=\"http://104.197.155.244:8080\">http://104.197.155.244:8080</a> )to generate different queries for the search engine:</b> <br><br>" +
              "Note: If count is not specified, by default 5 search results are obtained <br><br>" +
              "Note: If running the web service locally, replace the IP(http://104.197.155.244/) in the below and above example urls by \"localhost\"<br><br>" +
              "<br><b>Language based search</b><br><br>" +
              "1. Searching all projects for a specified langugage(for example, java)<br>&nbsp;&nbsp;&nbsp;Extension: ?language=java <br>" +
              "<br>So you enter the URL: <a href=\"http://104.197.155.244:8080/?language=java\">http://104.197.155.244:8080/?language=java</a>" +
              "<br><b> Some of the langugages used in the indexed projects are: Java, Go, PHP, JS, CSS, HTML, XML</b><br><br>" +
              "2. Searching all projects for all languages:" +
              "<br>&nbsp;&nbsp;&nbsp;Extension: language=all<br><br>" +

              "3. If you want to specify count(eg. 5) along with above parameters, just add the parameter(add below extension preceded by '&'):" +
              "<br>&nbsp;&nbsp;&nbsp;Extension: count=5<br>" +
              "&nbsp;So you enter the URL: <a href=\"http://104.197.155.244:8080/?language=java&count=5\">http://104.197.155.244:8080/?language=java&count=5</a>" +
              "<br><br>4. Other parameters possible are(<b>only language is necessary, all others optional, all parameters take only single values " +
              "and can be written in any order</b>):" +
              "<br>&nbsp;&nbsp;&nbsp;Extension: projectname=eclipse" +
              "<br>&nbsp;&nbsp;&nbsp;Extension: keyword=search_term<br>" +
              "<br><br><b>Tag based search</b><br>" +
              "You can also search all projects by specifying a tag. Tags are added to all projects." +
              " This search will return the project containing the spcified tag in their tag list<br>" +
              "Use below query to give a tag to search in all project tags:<br><br>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;" +
              "<a href=\"http://104.197.155.244:8080/?tags=enter_tag_here\">http://104.197.155.244:8080/?tags=enter_tag_here</a>" +
              "<br><br><b>This tag parameter cannot be combined with any other langugage based search parameters</b>" +


              "<h2>Some example queries:</h2>" +
              "<a href=\"http://104.197.155.244:8080/?language=java\">http://104.197.155.244:8080/?language=java</a><br>" +
              "<a href=\"http://104.197.155.244:8080/?language=java&count=1\">http://104.197.155.244:8080/?language=java&count=1</a><br>" +
              "<a href=\"http://104.197.155.244:8080/?language=java&projectname=eclipse\">http://104.197.155.244:8080/?language=java&projectname=eclipse</a><br>" +
              "<a href=\"http://104.197.155.244:8080/?language=java&projectname=eclipse&count=2\">http://104.197.155.244:8080/?language=java&projectname=eclipse&count=2</a><br>" +
              "<a href=\"http://104.197.155.244:8080/?language=java&projectname=eclipse&keyword=connector\">http://104.197.155.244:8080/?language=java&projectname=eclipse&keyword=connector</a><br>" +
              "<a href=\"http://104.197.155.244:8080/?tags=security\">http://104.197.155.244:8080/?tags=security</a><br>"))


            //Give template response
          }
        }
    }

    //Create second handler/route which takes parameters language and other optional parameters
    object Route2 {
      val route =
        parameters('language, 'count ? "5", 'projectname ? "", 'keyword ? "") { (language, count, projectname, keyword) =>

          //Above speficied parameters can be taken from web service, whith default value for optional params

          //SPecify timeout value
          implicit val timeout = Timeout(1000 seconds)
          var result: String = ""
          try {

            //Pass a message to first actor, and use a future to get its response,this actor removes stopwords from search keyword
            val future1 = discardstopwords ? Discard(keyword.toLowerCase)
            val keyword_cleaned = Await.result(future1, timeout.duration).asInstanceOf[String].toString

            //Pass a message to scond actor, and use a future to get its response,this actor makes a call to the elastic search engine and gets json response
            val future2 = fetch ? FetchList1(language.toLowerCase, count, projectname.toLowerCase.replaceAll(" ", "* *"), keyword_cleaned.toLowerCase)
            result = Await.result(future2, timeout.duration).asInstanceOf[String]

          }
          catch {
            case ex: Exception => {
              result += "<b>There was an error.</b><br><br>"
              result += ex.toString
            }
          }
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, if (result.equals("")) "Sorry! No results found." else result))

          //display search results through web service
        }
    }

    //similar to Route2 handler, this object takes tags as parameter input from web service
    object Route3 {
      val route =
        parameters('tags, 'count ? "5") { (tags, count) =>

          //Above speficied parameters can be taken from web service, whith default value for optional params

          //SPecify timeout value
          implicit val timeout = Timeout(1000 seconds)
          var result: String = ""
          try {

            //Pass a message to second actor, and use a future to get its response,this actor makes a call to the elastic search engine and gets json response

            val future = fetch ? FetchList2(tags.toLowerCase, count)
            result = Await.result(future, timeout.duration).asInstanceOf[String]


          }
          catch {
            case ex: Exception => {
              result += "<b>There was an error.</b><br><br>"
              result += ex.getMessage.toString
            }
          }
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, if (result.equals("")) "Sorry! No results found." else result))
          //display search results through web service
        }
    }

    //specify different handlers(called routes here) for our web service
    object MainRouter {
      val routes = Route2.route ~ Route3.route ~ Route1.route
    }

    //It starts an HTTP Server on 104.197.155.244 and port 8080 and replies to GET requests using routes/handler specified
    val bindingFuture = Http().bindAndHandle(MainRouter.routes, "0.0.0.0", 8080)

    println(s"Check Server online at http://104.197.155.244:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done


  }
}

//This actor is used to discard stopwords from search keyword and sends back filtered keywords to sender
class discardStopwords extends Actor{


  def receive = {
    case Discard(keyword) =>



      var keyword_withspaces=" "+keyword+" "

      //hard coded stopwords list, these words are common language specific words that can be discarded from search
      val stopwords="for , foreach , while , if , not , is , and , or , case , ref , print , println , system , out , import , var , val , return , private , public , class , function , click , else , void , interface , assert , def , include , a , the , an"
      val stopwords_list=stopwords.replaceAll(" ","").split(",")
      for(word <- stopwords_list)
      {
        keyword_withspaces=keyword_withspaces.replaceAll(" "+word+" "," ")
      }
      //modify search keyword string to discard stopwords

      //trim spaces
      keyword_withspaces=keyword_withspaces.replaceAll("^ ","").replaceAll(" $","")

      //send filtered keywords to sender
      sender ! keyword_withspaces.toString

  }
}

//this actor send search query to elastic search engine and fetches json reponse
class Fetch(parseresponse : ActorRef) extends Actor {
  var iterationCount = 0
  implicit val timeout = Timeout(1000 seconds)
  lazy private val settings = Settings.settingsBuilder().put("cluster.name", "elasticsearch")
    .build()
  //settings and address for elastic search cluster

  val inetAddress: InetAddress = InetAddress.getByName("104.197.155.244")

  val client: Client = TransportClient.builder().settings(settings).build().addTransportAddresses(new InetSocketTransportAddress(inetAddress, 9300))
  //create elastic search client

  //stop actor if actor is called more than 100 times
  def incrementAndCheck {
    iterationCount += 1;
    if (iterationCount > 99)
      context.stop(self)
  }


  def receive = {

    //this case makes search query when language is mentioned in search parameters
    case FetchList1(lang, count, projectname,keyword) =>
      incrementAndCheck


      var response:SearchResponse=new SearchResponse()

      //if no search keywords entered
      if(keyword.equals("")) {

        //different search qeuries for different values entered for params, get back json response
        if(lang.equals("all"))
          response = client.prepareSearch("languages").setSearchType(SearchType.DFS_QUERY_THEN_FETCH).
            setQuery(QueryBuilders.wildcardQuery("projectname", "*" + projectname + "*")).setSize(count.toInt).get()

        else
          response = client.prepareSearch("languages").setTypes(lang).setSearchType(SearchType.DFS_QUERY_THEN_FETCH).
            setQuery(QueryBuilders.wildcardQuery("projectname", "*" + projectname + "*")).setSize(count.toInt).get()

      }
      else{
        //different search queries for different values entered for params, get back json response

        if(lang.equals("all"))
          response = client.prepareSearch("languages").setSearchType(SearchType.DFS_QUERY_THEN_FETCH).
            setQuery(QueryBuilders.wildcardQuery("projectname", "*" + projectname + "*"))
            .setQuery(QueryBuilders.multiMatchQuery(keyword, "description", "content", "projectname")).
            setSize(count.toInt).get()

        else
          response = client.prepareSearch("languages").setTypes(lang).setSearchType(SearchType.DFS_QUERY_THEN_FETCH).
            setQuery(QueryBuilders.wildcardQuery("projectname", "*" + projectname + "*"))
            .setQuery(QueryBuilders.multiMatchQuery(keyword, "description", "content", "projectname")).
            setSize(count.toInt).get()
      }



      //  pass json response to third actor ParseReponse for processing, and use a future to get its response
      val future = parseresponse ? Parse1(response)
      var result = Await.result(future, timeout.duration).asInstanceOf[String]

      sender ! result.toString //      send final search response back to sender


    //this case makes search query when tags are mentioned in search parameters

    case FetchList2(tags,count) =>
      incrementAndCheck

      var tags_list=tags.split(" ")


      var response:SearchResponse=new SearchResponse()

      //search first tag entered as index in elastic search
      response = client.prepareSearch("tags").
        setQuery(QueryBuilders.wildcardQuery("content", "*" + tags_list(0) + "*"))
        .setSize(count.toInt).get()


      //  pass json response to third actor ParseReponse for processing, and use a future to get its response
      val future = parseresponse ? Parse2(response)
      var result = Await.result(future, timeout.duration).asInstanceOf[String]


      //      send final search response back to sender

      sender ! result.toString

  }
}


//this actor parses json response and modifies it into a string which is the final response to be displayed from web service

class ParseResponse extends Actor {

  def receive = {

    //this case is used which language param is entered through web service

    case Parse1(response) =>

      //get number of responses of search query
      val num_hits=response.getHits.getHits.length
      val json_response=Json.parse(response.toString)

//get total time take for search
      val responseTime=(json_response \ "took").toString()

      var a = 0;

      //create a string below and which store our final response to be displayed
      //this final response contains html tags as we are displaying it on the reponse page of web service

      var final_response: String = new String()
      final_response+=s"<b>ElasticSearch responded with results in ${responseTime} ms</b><br><br>"
      if(num_hits==0)
      {final_response=""}

      // for loop execution with a range, below we parse json to get file names, project names etc for all responses
      for( a <- 0 to num_hits-1){
        val b=a+1

        //getting json field values anf storing in final response

        final_response+=s"<b>Result # $b </b><br>"
        val file_details_list = new ListBuffer[String]()
        final_response+="File Name: "
        final_response+= ((json_response \ "hits" \ "hits")(a) \ "_source" \ "filename" ).toString()

        final_response+="<br>Project Name: "
        final_response+=((json_response \ "hits" \ "hits")(a) \ "_source" \ "projectname" ).toString()
        final_response+="<br>Project URL: "
        final_response+="<a href=\""+
          ((json_response \ "hits" \ "hits")(a) \ "_source" \ "projecturl" ).toString().replaceAll("\"","")+
          "\">"+((json_response \ "hits" \ "hits")(a) \ "_source" \ "projecturl" ).toString().replaceAll("\"","")+"</a>"

        final_response+="<br>Project Description: "
        final_response+=((json_response \ "hits" \ "hits")(a) \ "_source" \ "description" ).toString()


        final_response+="<br><br>"


      }



      //      send final search response to be displayed back to sender

      sender ! final_response.toString()



    //this case is used which tags param is entered through web service, methodology is similar to above case Parse1
    case Parse2(response) =>

      val num_hits=response.getHits.getHits.length
      val json_response=Json.parse(response.toString)


      val responseTime=(json_response \ "took").toString()

      var a = 0;

      //create a string below and which store our final response to be displayed
      //this final response contains html tags as we are displaying it on the reponse page of web service

      var final_response: String = new String()
      final_response+=s"<b>ElasticSearch responded with results in ${responseTime} ms</b><br><br>"
      if(num_hits==0)
      {final_response=""}

      // for loop execution with a range


      for( a <- 0 to num_hits-1){
        val b=a+1
        final_response+=s"<b>Result # $b </b><br>"

        //getting json field values anf storing in final response

        final_response+="<br>Project Name: "
        final_response+=((json_response \ "hits" \ "hits")(a) \ "_source" \ "projectname" ).toString()
        final_response+="<br>Project URL: "
        final_response+="<a href=\""+
          ((json_response \ "hits" \ "hits")(a) \ "_source" \ "projecturl" ).toString().replaceAll("\"","")+
          "\">"+((json_response \ "hits" \ "hits")(a) \ "_source" \ "projecturl" ).toString().replaceAll("\"","")+"</a>"

        final_response+="<br>Project Description: "
        final_response+=((json_response \ "hits" \ "hits")(a) \ "_source" \ "description" ).toString()
        final_response+="<br>Project Tags: "
        final_response+=((json_response \ "hits" \ "hits")(a) \ "_source" \ "content" ).toString().replaceAll(" ",", ")



        final_response+="<br><br>"



      }



      //      send final search response to be displayed back to sender

      sender ! final_response.toString()

  }

}