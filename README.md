streamdrill-client
==================

stream<b>drill</b> client library and examples

The submodules contain the client libraries for different languages as well as some examples that use the client library.

The simple client library makes it very easy to access your [stream<b>drill</b>[(http://streamdrill.com) instance.
It is available in Scala and Python currently. However, if you want to use your own REST library, please check
the [authorization section](http://demo.streamdrill.com/docs/?p=api#auth) to ensure the correct header values are set.

First, check out the client code together with the examples and compile it:

    git clone https://github.com/thinkberg/streamdrill-client.git
    cd streamdrill-client
    mvn package

That's it, now you should be ready to [try the examples](http://demo.streamdrill.com/docs/?p=examples).
